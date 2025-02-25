/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty5.handler.codec.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.WriteBufferWaterMark;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpScheme;
import io.netty5.handler.codec.http2.Http2Exception.StreamException;
import io.netty5.util.AsciiString;
import io.netty5.util.AttributeKey;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.ImmediateEventExecutor;
import io.netty5.util.concurrent.Promise;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.netty5.handler.codec.http2.Http2TestUtil.anyHttp2Settings;
import static io.netty5.handler.codec.http2.Http2TestUtil.assertEqualsAndRelease;
import static io.netty5.handler.codec.http2.Http2TestUtil.bb;
import static io.netty5.util.ReferenceCountUtil.release;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public abstract class Http2MultiplexTest<C extends Http2FrameCodec> {
    private final Http2Headers request = new DefaultHttp2Headers()
            .method(HttpMethod.GET.asciiName()).scheme(HttpScheme.HTTPS.name())
            .authority(new AsciiString("example.org")).path(new AsciiString("/foo"));

    private EmbeddedChannel parentChannel;
    private Http2FrameWriter frameWriter;
    private Http2FrameInboundWriter frameInboundWriter;
    private TestChannelInitializer childChannelInitializer;
    private C codec;

    private static final int initialRemoteStreamWindow = 1024;

    protected abstract C newCodec(TestChannelInitializer childChannelInitializer,  Http2FrameWriter frameWriter);
    protected abstract ChannelHandler newMultiplexer(TestChannelInitializer childChannelInitializer);

    @BeforeEach
    public void setUp() {
        childChannelInitializer = new TestChannelInitializer();
        parentChannel = new EmbeddedChannel();
        frameInboundWriter = new Http2FrameInboundWriter(parentChannel);
        parentChannel.connect(new InetSocketAddress(0));
        frameWriter = Http2TestUtil.mockedFrameWriter();
        codec = newCodec(childChannelInitializer, frameWriter);
        parentChannel.pipeline().addLast(codec);
        ChannelHandler multiplexer = newMultiplexer(childChannelInitializer);
        if (multiplexer != null) {
            parentChannel.pipeline().addLast(multiplexer);
        }

        parentChannel.pipeline().fireChannelActive();

        parentChannel.writeInbound(Http2CodecUtil.connectionPrefaceBuf());

        Http2Settings settings = new Http2Settings().initialWindowSize(initialRemoteStreamWindow);
        frameInboundWriter.writeInboundSettings(settings);

        verify(frameWriter).writeSettingsAck(any(ChannelHandlerContext.class));

        frameInboundWriter.writeInboundSettingsAck();

        Http2SettingsFrame settingsFrame = parentChannel.readInbound();
        assertNotNull(settingsFrame);
        Http2SettingsAckFrame settingsAckFrame = parentChannel.readInbound();
        assertNotNull(settingsAckFrame);

        // Handshake
        verify(frameWriter).writeSettings(any(ChannelHandlerContext.class),
                anyHttp2Settings());
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (childChannelInitializer.handler instanceof LastInboundHandler) {
            ((LastInboundHandler) childChannelInitializer.handler).finishAndReleaseAll();
        }
        parentChannel.finishAndReleaseAll();
        codec = null;
    }

    // TODO(buchgr): Flush from child channel
    // TODO(buchgr): ChildChannel.childReadComplete()
    // TODO(buchgr): GOAWAY Logic
    // TODO(buchgr): Test ChannelConfig.setMaxMessagesPerRead

    @Test
    public void writeUnknownFrame() {
        Http2StreamChannel childChannel = newOutboundStream(new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
                ctx.writeAndFlush(new DefaultHttp2UnknownFrame((byte) 99, new Http2Flags()));
                ctx.fireChannelActive();
            }
        });
        assertTrue(childChannel.isActive());

        verify(frameWriter).writeFrame(any(ChannelHandlerContext.class), eq((byte) 99), eqStreamId(childChannel),
                any(Http2Flags.class), any(ByteBuf.class));
    }

    private Http2StreamChannel newInboundStream(int streamId, boolean endStream, final ChannelHandler childHandler) {
        return newInboundStream(streamId, endStream, null, childHandler);
    }

    private Http2StreamChannel newInboundStream(int streamId, boolean endStream,
                                                AtomicInteger maxReads, final ChannelHandler childHandler) {
        final AtomicReference<Http2StreamChannel> streamChannelRef = new AtomicReference<Http2StreamChannel>();
        childChannelInitializer.maxReads = maxReads;
        childChannelInitializer.handler = new ChannelHandler() {
            @Override
            public void channelRegistered(ChannelHandlerContext ctx) {
                assertNull(streamChannelRef.get());
                streamChannelRef.set((Http2StreamChannel) ctx.channel());
                ctx.pipeline().addLast(childHandler);
                ctx.fireChannelRegistered();
            }
        };

        frameInboundWriter.writeInboundHeaders(streamId, request, 0, endStream);
        Http2StreamChannel channel = streamChannelRef.get();
        assertEquals(streamId, channel.stream().id());
        return channel;
    }

    @Test
    public void readUnkownFrame() {
        LastInboundHandler handler = new LastInboundHandler();

        Http2StreamChannel channel = newInboundStream(3, true, handler);
        frameInboundWriter.writeInboundFrame((byte) 99, channel.stream().id(), new Http2Flags(), Unpooled.EMPTY_BUFFER);

        // header frame and unknown frame
        verifyFramesMultiplexedToCorrectChannel(channel, handler, 2);

        Channel childChannel = newOutboundStream(new ChannelHandler() { });
        assertTrue(childChannel.isActive());
    }

    @Test
    public void headerAndDataFramesShouldBeDelivered() {
        LastInboundHandler inboundHandler = new LastInboundHandler();

        Http2StreamChannel channel = newInboundStream(3, false, inboundHandler);
        Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(request).stream(channel.stream());
        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("hello")).stream(channel.stream());
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("world")).stream(channel.stream());

        assertTrue(inboundHandler.isChannelActive());
        frameInboundWriter.writeInboundData(channel.stream().id(), bb("hello"), 0, false);
        frameInboundWriter.writeInboundData(channel.stream().id(), bb("world"), 0, false);

        assertEquals(headersFrame, inboundHandler.readInbound());

        assertEqualsAndRelease(dataFrame1, inboundHandler.readInbound());
        assertEqualsAndRelease(dataFrame2, inboundHandler.readInbound());

        assertNull(inboundHandler.readInbound());
    }

    @Test
    public void headerMultipleContentLengthValidationShouldPropagate() {
        headerMultipleContentLengthValidationShouldPropagate(false);
    }

    @Test
    public void headerMultipleContentLengthValidationShouldPropagateWithEndStream() {
        headerMultipleContentLengthValidationShouldPropagate(true);
    }

    private void headerMultipleContentLengthValidationShouldPropagate(boolean endStream) {
        final LastInboundHandler inboundHandler = new LastInboundHandler();
        request.addLong(HttpHeaderNames.CONTENT_LENGTH, 0);
        request.addLong(HttpHeaderNames.CONTENT_LENGTH, 1);
        Http2StreamChannel channel = newInboundStream(3, endStream, inboundHandler);

        assertThrows(StreamException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                inboundHandler.checkException();
            }
        });
        assertNull(inboundHandler.readInbound());
        assertFalse(channel.isActive());
    }

    @Test
    public void headerPlusSignContentLengthValidationShouldPropagate() {
        headerSignContentLengthValidationShouldPropagateWithEndStream(false, false);
    }

    @Test
    public void headerPlusSignContentLengthValidationShouldPropagateWithEndStream() {
        headerSignContentLengthValidationShouldPropagateWithEndStream(false, true);
    }

    @Test
    public void headerMinusSignContentLengthValidationShouldPropagate() {
        headerSignContentLengthValidationShouldPropagateWithEndStream(true, false);
    }

    @Test
    public void headerMinusSignContentLengthValidationShouldPropagateWithEndStream() {
        headerSignContentLengthValidationShouldPropagateWithEndStream(true, true);
    }

    private void headerSignContentLengthValidationShouldPropagateWithEndStream(boolean minus, boolean endStream) {
        final LastInboundHandler inboundHandler = new LastInboundHandler();
        request.add(HttpHeaderNames.CONTENT_LENGTH, (minus ? "-" : "+") + 1);
        Http2StreamChannel channel = newInboundStream(3, endStream, inboundHandler);
        assertThrows(StreamException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                inboundHandler.checkException();
            }
        });

        assertNull(inboundHandler.readInbound());
        assertFalse(channel.isActive());
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagate() {
        headerContentLengthNotMatchValidationShouldPropagate(false, false, false);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateWithEndStream() {
        headerContentLengthNotMatchValidationShouldPropagate(false, true, false);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateCloseLocal() {
        headerContentLengthNotMatchValidationShouldPropagate(true, false, false);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateWithEndStreamCloseLocal() {
        headerContentLengthNotMatchValidationShouldPropagate(true, true, false);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateTrailers() {
        headerContentLengthNotMatchValidationShouldPropagate(false, false, true);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateWithEndStreamTrailers() {
        headerContentLengthNotMatchValidationShouldPropagate(false, true, true);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateCloseLocalTrailers() {
        headerContentLengthNotMatchValidationShouldPropagate(true, false, true);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateWithEndStreamCloseLocalTrailers() {
        headerContentLengthNotMatchValidationShouldPropagate(true, true, true);
    }

    private void headerContentLengthNotMatchValidationShouldPropagate(
            boolean closeLocal, boolean endStream, boolean trailer) {
        final LastInboundHandler inboundHandler = new LastInboundHandler();
        request.addLong(HttpHeaderNames.CONTENT_LENGTH, 1);
        Http2StreamChannel channel = newInboundStream(3, false, inboundHandler);
        assertTrue(channel.isActive());

        if (closeLocal) {
            channel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers(), true))
                    .syncUninterruptibly();
            assertEquals(Http2Stream.State.HALF_CLOSED_LOCAL, channel.stream().state());
        } else {
            assertEquals(Http2Stream.State.OPEN, channel.stream().state());
        }

        if (trailer) {
            frameInboundWriter.writeInboundHeaders(channel.stream().id(), new DefaultHttp2Headers(), 0, endStream);
        } else {
            frameInboundWriter.writeInboundData(channel.stream().id(), bb("foo"), 0, endStream);
        }

        assertThrows(StreamException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                inboundHandler.checkException();
            }
        });

        Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(request).stream(channel.stream());
        assertEquals(headersFrame, inboundHandler.readInbound());
        assertNull(inboundHandler.readInbound());
        assertFalse(channel.isActive());
    }

    @Test
    public void framesShouldBeMultiplexed() {
        LastInboundHandler handler1 = new LastInboundHandler();
        Http2StreamChannel channel1 = newInboundStream(3, false, handler1);
        LastInboundHandler handler2 = new LastInboundHandler();
        Http2StreamChannel channel2 = newInboundStream(5, false, handler2);
        LastInboundHandler handler3 = new LastInboundHandler();
        Http2StreamChannel channel3 = newInboundStream(11, false, handler3);

        verifyFramesMultiplexedToCorrectChannel(channel1, handler1, 1);
        verifyFramesMultiplexedToCorrectChannel(channel2, handler2, 1);
        verifyFramesMultiplexedToCorrectChannel(channel3, handler3, 1);

        frameInboundWriter.writeInboundData(channel2.stream().id(), bb("hello"), 0, false);
        frameInboundWriter.writeInboundData(channel1.stream().id(), bb("foo"), 0, true);
        frameInboundWriter.writeInboundData(channel2.stream().id(), bb("world"), 0, true);
        frameInboundWriter.writeInboundData(channel3.stream().id(), bb("bar"), 0, true);

        verifyFramesMultiplexedToCorrectChannel(channel1, handler1, 1);
        verifyFramesMultiplexedToCorrectChannel(channel2, handler2, 2);
        verifyFramesMultiplexedToCorrectChannel(channel3, handler3, 1);
    }

    @Test
    public void inboundDataFrameShouldUpdateLocalFlowController() throws Http2Exception {
        Http2LocalFlowController flowController = Mockito.mock(Http2LocalFlowController.class);
        codec.connection().local().flowController(flowController);

        LastInboundHandler handler = new LastInboundHandler();
        final Http2StreamChannel channel = newInboundStream(3, false, handler);

        ByteBuf tenBytes = bb("0123456789");

        frameInboundWriter.writeInboundData(channel.stream().id(), tenBytes, 0, true);

        // Verify we marked the bytes as consumed
        verify(flowController).consumeBytes(argThat(http2Stream -> http2Stream.id() == channel.stream().id()), eq(10));

        // headers and data frame
        verifyFramesMultiplexedToCorrectChannel(channel, handler, 2);
    }

    @Test
    public void unhandledHttp2FramesShouldBePropagated() {
        Http2PingFrame pingFrame = new DefaultHttp2PingFrame(0);
        frameInboundWriter.writeInboundPing(false, 0);
        assertEquals(parentChannel.readInbound(), pingFrame);

        DefaultHttp2GoAwayFrame goAwayFrame = new DefaultHttp2GoAwayFrame(1,
                parentChannel.alloc().buffer().writeLong(8));
        frameInboundWriter.writeInboundGoAway(0, goAwayFrame.errorCode(), goAwayFrame.content().retainedDuplicate());

        Http2GoAwayFrame frame = parentChannel.readInbound();
        assertEqualsAndRelease(frame, goAwayFrame);
    }

    @Test
    public void channelReadShouldRespectAutoRead() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        assertTrue(childChannel.config().isAutoRead());
        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        childChannel.config().setAutoRead(false);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world"), 0, false);
        Http2DataFrame dataFrame0 = inboundHandler.readInbound();
        assertNotNull(dataFrame0);
        release(dataFrame0);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, false);

        assertNull(inboundHandler.readInbound());

        childChannel.config().setAutoRead(true);
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 2);
    }

    @Test
    public void channelReadShouldRespectAutoReadAndNotProduceNPE() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        assertTrue(childChannel.config().isAutoRead());
        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        childChannel.config().setAutoRead(false);
        childChannel.pipeline().addFirst(new ChannelHandler() {
            private int count;
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.fireChannelRead(msg);
                // Close channel after 2 reads so there is still something in the inboundBuffer when the close happens.
                if (++count == 2) {
                    ctx.close();
                }
            }
        });
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world"), 0, false);
        Http2DataFrame dataFrame0 = inboundHandler.readInbound();
        assertNotNull(dataFrame0);
        release(dataFrame0);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, false);

        assertNull(inboundHandler.readInbound());

        childChannel.config().setAutoRead(true);
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 3);
        inboundHandler.checkException();
    }

    @Test
    public void readInChannelReadWithoutAutoRead() {
        useReadWithoutAutoRead(false);
    }

    @Test
    public void readInChannelReadCompleteWithoutAutoRead() {
        useReadWithoutAutoRead(true);
    }

    private void useReadWithoutAutoRead(final boolean readComplete) {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        assertTrue(childChannel.config().isAutoRead());
        childChannel.config().setAutoRead(false);
        assertFalse(childChannel.config().isAutoRead());

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        // Add a handler which will request reads.
        childChannel.pipeline().addFirst(new ChannelHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.fireChannelRead(msg);
                if (!readComplete) {
                    ctx.read();
                }
            }

            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                ctx.fireChannelReadComplete();
                if (readComplete) {
                    ctx.read();
                }
            }
        });

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, true);

        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 6);
    }

    private Http2StreamChannel newOutboundStream(ChannelHandler handler) {
        Future<Http2StreamChannel> future = new Http2StreamChannelBootstrap(parentChannel).handler(handler)
                .open();
        return future.syncUninterruptibly().getNow();
    }

    /**
     * A child channel for an HTTP/2 stream in IDLE state (that is no headers sent or received),
     * should not emit a RST_STREAM frame on close, as this is a connection error of type protocol error.
     */
    @Test
    public void idleOutboundStreamShouldNotWriteResetFrameOnClose() {
        LastInboundHandler handler = new LastInboundHandler();

        Channel childChannel = newOutboundStream(handler);
        assertTrue(childChannel.isActive());

        childChannel.close();

        assertFalse(childChannel.isOpen());
        assertFalse(childChannel.isActive());
        assertNull(parentChannel.readOutbound());
    }

    @Test
    public void outboundStreamShouldWriteResetFrameOnClose_headersSent() {
        ChannelHandler handler = new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
                ctx.fireChannelActive();
            }
        };

        Http2StreamChannel childChannel = newOutboundStream(handler);
        assertTrue(childChannel.isActive());

        childChannel.close();
        verify(frameWriter).writeRstStream(any(ChannelHandlerContext.class),
                eqStreamId(childChannel), eq(Http2Error.CANCEL.code()));
    }

    @Test
    public void outboundStreamShouldNotWriteResetFrameOnClose_IfStreamDidntExist() {
        when(frameWriter.writeHeaders(any(ChannelHandlerContext.class), anyInt(),
                any(Http2Headers.class), anyInt(), anyBoolean())).thenAnswer(new Answer<Future<Void>>() {

            private boolean headersWritten;
            @Override
            public Future<Void> answer(InvocationOnMock invocationOnMock) {
                // We want to fail to write the first headers frame. This is what happens if the connection
                // refuses to allocate a new stream due to having received a GOAWAY.
                if (!headersWritten) {
                    headersWritten = true;
                    return ImmediateEventExecutor.INSTANCE.newFailedFuture(new Exception("boom"));
                }
                return ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
            }
        });

        Http2StreamChannel childChannel = newOutboundStream(new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
                ctx.fireChannelActive();
            }
        });

        assertFalse(childChannel.isActive());

        childChannel.close();
        // The channel was never active so we should not generate a RST frame.
        verify(frameWriter, never()).writeRstStream(any(ChannelHandlerContext.class),
                eqStreamId(childChannel), anyLong());

        assertTrue(parentChannel.outboundMessages().isEmpty());
    }

    @Test
    public void inboundRstStreamFireChannelInactive() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel channel = newInboundStream(3, false, inboundHandler);
        assertTrue(inboundHandler.isChannelActive());
        frameInboundWriter.writeInboundRstStream(channel.stream().id(), Http2Error.INTERNAL_ERROR.code());

        assertFalse(inboundHandler.isChannelActive());

        // A RST_STREAM frame should NOT be emitted, as we received a RST_STREAM.
        verify(frameWriter, never()).writeRstStream(any(ChannelHandlerContext.class), eqStreamId(channel),
                anyLong());
    }

    @Test
    public void streamExceptionTriggersChildChannelExceptionAndClose() throws Exception {
        final LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel channel = newInboundStream(3, false, inboundHandler);
        assertTrue(channel.isActive());
        StreamException cause = new StreamException(channel.stream().id(), Http2Error.PROTOCOL_ERROR, "baaam!");
        parentChannel.pipeline().fireExceptionCaught(cause);

        assertFalse(channel.isActive());

        assertThrows(StreamException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                inboundHandler.checkException();
            }
        });
    }

    @Test
    public void streamClosedErrorTranslatedToClosedChannelExceptionOnWrites() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();

        final Http2StreamChannel childChannel = newOutboundStream(inboundHandler);
        assertTrue(childChannel.isActive());

        Http2Headers headers = new DefaultHttp2Headers();
        when(frameWriter.writeHeaders(any(ChannelHandlerContext.class), anyInt(),
                eq(headers), anyInt(), anyBoolean())).thenAnswer(invocationOnMock ->
            ImmediateEventExecutor.INSTANCE.newFailedFuture(
                        new StreamException(childChannel.stream().id(), Http2Error.STREAM_CLOSED, "Stream Closed")));
        final Future<Void> future = childChannel.writeAndFlush(
                new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));

        parentChannel.flush();

        assertFalse(childChannel.isActive());
        assertFalse(childChannel.isOpen());

        inboundHandler.checkException();

        CompletionException e = assertThrows(CompletionException.class, new Executable() {
            @Override
            public void execute() {
                future.syncUninterruptibly();
            }
        });
        assertThat(e.getCause(), CoreMatchers.instanceOf(ClosedChannelException.class));
    }

    @Test
    public void creatingWritingReadingAndClosingOutboundStreamShouldWork() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newOutboundStream(inboundHandler);
        assertTrue(childChannel.isActive());
        assertTrue(inboundHandler.isChannelActive());

        // Write to the child channel
        Http2Headers headers = new DefaultHttp2Headers().scheme("https").method("GET").path("/foo.txt");
        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers));

        // Read from the child channel
        frameInboundWriter.writeInboundHeaders(childChannel.stream().id(), headers, 0, false);

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);
        assertEquals(headers, headersFrame.headers());

        // Close the child channel.
        childChannel.close();

        // An active outbound stream should emit a RST_STREAM frame.
        verify(frameWriter).writeRstStream(any(ChannelHandlerContext.class), eqStreamId(childChannel),
                anyLong());

        assertFalse(childChannel.isOpen());
        assertFalse(childChannel.isActive());
        assertFalse(inboundHandler.isChannelActive());
    }

    // Test failing the promise of the first headers frame of an outbound stream. In practice this error case would most
    // likely happen due to the max concurrent streams limit being hit or the channel running out of stream identifiers.
    //
    @Test
    public void failedOutboundStreamCreationThrowsAndClosesChannel() throws Exception {
        LastInboundHandler handler = new LastInboundHandler();
        Http2StreamChannel childChannel = newOutboundStream(handler);
        assertTrue(childChannel.isActive());

        Http2Headers headers = new DefaultHttp2Headers();
        when(frameWriter.writeHeaders(any(ChannelHandlerContext.class), anyInt(),
               eq(headers), anyInt(), anyBoolean())).thenAnswer(invocationOnMock ->
               ImmediateEventExecutor.INSTANCE.newFailedFuture(new Http2NoMoreStreamIdsException()));

        final Future<Void> future = childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers));
        parentChannel.flush();

        assertFalse(childChannel.isActive());
        assertFalse(childChannel.isOpen());

        handler.checkException();

        CompletionException e = assertThrows(CompletionException.class, new Executable() {
            @Override
            public void execute() {
                future.syncUninterruptibly();
            }
        });
        assertThat(e.getCause(), CoreMatchers.instanceOf(Http2NoMoreStreamIdsException.class));
    }

    @Test
    public void channelClosedWhenCloseListenerCompletes() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);

        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());

        final AtomicBoolean channelOpen = new AtomicBoolean(true);
        final AtomicBoolean channelActive = new AtomicBoolean(true);

        // Create a promise before actually doing the close, because otherwise we would be adding a listener to a future
        // that is already completed because we are using EmbeddedChannel which executes code in the JUnit thread.
        Promise<Void> p = childChannel.newPromise();
        p.asFuture().addListener(childChannel, (channel, future) -> {
            channelOpen.set(channel.isOpen());
            channelActive.set(channel.isActive());
        });
        childChannel.close().cascadeTo(p).syncUninterruptibly();

        assertFalse(channelOpen.get());
        assertFalse(channelActive.get());
        assertFalse(childChannel.isActive());
    }

    @Test
    public void channelClosedWhenChannelClosePromiseCompletes() {
         LastInboundHandler inboundHandler = new LastInboundHandler();
         Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);

         assertTrue(childChannel.isOpen());
         assertTrue(childChannel.isActive());

         final AtomicBoolean channelOpen = new AtomicBoolean(true);
         final AtomicBoolean channelActive = new AtomicBoolean(true);

         childChannel.closeFuture().addListener(childChannel, (channel, future) -> {
             channelOpen.set(channel.isOpen());
             channelActive.set(channel.isActive());
         });
         childChannel.close().syncUninterruptibly();

         assertFalse(channelOpen.get());
         assertFalse(channelActive.get());
         assertFalse(childChannel.isActive());
    }

    @Test
    public void channelClosedWhenWriteFutureFails() {
        final Queue<Promise<Void>> writePromises = new ArrayDeque<>();

        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);

        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());

        final AtomicBoolean channelOpen = new AtomicBoolean(true);
        final AtomicBoolean channelActive = new AtomicBoolean(true);

        Http2Headers headers = new DefaultHttp2Headers();
        when(frameWriter.writeHeaders(any(ChannelHandlerContext.class), anyInt(),
                eq(headers), anyInt(), anyBoolean())).thenAnswer(invocationOnMock -> {
            Promise<Void> promise = ImmediateEventExecutor.INSTANCE.newPromise();
            writePromises.offer(promise);
            return promise;
        });

        Future<Void> f = childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers));
        assertFalse(f.isDone());
        f.addListener(childChannel, (channel, future)-> {
            channelOpen.set(channel.isOpen());
            channelActive.set(channel.isActive());
        });

        Promise<Void> first = writePromises.poll();
        first.setFailure(new ClosedChannelException());
        f.awaitUninterruptibly();

        assertFalse(channelOpen.get());
        assertFalse(channelActive.get());
        assertFalse(childChannel.isActive());
    }

    @Test
    public void channelClosedTwiceMarksPromiseAsSuccessful() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);

        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());
        childChannel.close().syncUninterruptibly();
        childChannel.close().syncUninterruptibly();

        assertFalse(childChannel.isOpen());
        assertFalse(childChannel.isActive());
    }

    @Test
    public void settingChannelOptsAndAttrs() {
        AttributeKey<String> key = AttributeKey.newInstance(UUID.randomUUID().toString());

        Channel childChannel = newOutboundStream(new ChannelHandler() { });
        childChannel.config().setAutoRead(false).setWriteSpinCount(1000);
        childChannel.attr(key).set("bar");
        assertFalse(childChannel.config().isAutoRead());
        assertEquals(1000, childChannel.config().getWriteSpinCount());
        assertEquals("bar", childChannel.attr(key).get());
    }

    @Test
    public void outboundFlowControlWritability() {
        Http2StreamChannel childChannel = newOutboundStream(new ChannelHandler() { });
        assertTrue(childChannel.isActive());

        assertTrue(childChannel.isWritable());
        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        parentChannel.flush();

        // Test for initial window size
        assertTrue(initialRemoteStreamWindow < childChannel.config().getWriteBufferHighWaterMark());

        assertTrue(childChannel.isWritable());
        childChannel.write(new DefaultHttp2DataFrame(Unpooled.buffer().writeZero(16 * 1024 * 1024)));
        assertEquals(0, childChannel.bytesBeforeUnwritable());
        assertFalse(childChannel.isWritable());
    }

    @Test
    public void writabilityOfParentIsRespected() {
        Http2StreamChannel childChannel = newOutboundStream(new ChannelHandler() { });
        childChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(2048, 4096));
        parentChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(256, 512));
        assertTrue(childChannel.isWritable());
        assertTrue(parentChannel.isActive());

        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        parentChannel.flush();

        assertTrue(childChannel.isWritable());
        childChannel.write(new DefaultHttp2DataFrame(Unpooled.buffer().writeZero(256)));
        assertTrue(childChannel.isWritable());
        childChannel.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.buffer().writeZero(512)));

        long bytesBeforeUnwritable = childChannel.bytesBeforeUnwritable();
        assertNotEquals(0, bytesBeforeUnwritable);
        // Add something to the ChannelOutboundBuffer of the parent to simulate queuing in the parents channel buffer
        // and verify that this only affect the writability of the parent channel while the child stays writable
        // until it used all of its credits.
        parentChannel.unsafe().outboundBuffer().addMessage(
                Unpooled.buffer().writeZero(800), 800, parentChannel.newPromise());
        assertFalse(parentChannel.isWritable());

        assertTrue(childChannel.isWritable());
        assertEquals(4096, childChannel.bytesBeforeUnwritable());

        // Flush everything which simulate writing everything to the socket.
        parentChannel.flush();
        assertTrue(parentChannel.isWritable());
        assertTrue(childChannel.isWritable());
        assertEquals(bytesBeforeUnwritable, childChannel.bytesBeforeUnwritable());

        Future<Void> future = childChannel.writeAndFlush(new DefaultHttp2DataFrame(
                Unpooled.buffer().writeZero((int) bytesBeforeUnwritable)));
        assertFalse(childChannel.isWritable());
        assertTrue(parentChannel.isWritable());

        parentChannel.flush();
        assertFalse(future.isDone());
        assertTrue(parentChannel.isWritable());
        assertFalse(childChannel.isWritable());

        // Now write an window update frame for the stream which then should ensure we will flush the bytes that were
        // queued in the RemoteFlowController before for the stream.
        frameInboundWriter.writeInboundWindowUpdate(childChannel.stream().id(), (int) bytesBeforeUnwritable);
        assertTrue(childChannel.isWritable());
        assertTrue(future.isDone());
    }

    @Test
    public void channelClosedWhenInactiveFired() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);

        final AtomicBoolean channelOpen = new AtomicBoolean(false);
        final AtomicBoolean channelActive = new AtomicBoolean(false);
        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());

        childChannel.pipeline().addLast(new ChannelHandler() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                channelOpen.set(ctx.channel().isOpen());
                channelActive.set(ctx.channel().isActive());

                ctx.fireChannelInactive();
            }
        });

        childChannel.close().syncUninterruptibly();
        assertFalse(channelOpen.get());
        assertFalse(channelActive.get());
    }

    @Test
    public void channelInactiveHappensAfterExceptionCaughtEvents() {
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicInteger exceptionCaught = new AtomicInteger(-1);
        final AtomicInteger channelInactive = new AtomicInteger(-1);
        final AtomicInteger channelUnregistered = new AtomicInteger(-1);
        Http2StreamChannel childChannel = newOutboundStream(new ChannelHandler() {

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                ctx.close();
                throw new Exception("exception");
            }
        });

        childChannel.pipeline().addLast(new ChannelHandler() {

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                channelInactive.set(count.getAndIncrement());
                ctx.fireChannelInactive();
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                exceptionCaught.set(count.getAndIncrement());
                ctx.fireExceptionCaught(cause);
            }

            @Override
            public void channelUnregistered(ChannelHandlerContext ctx) {
                channelUnregistered.set(count.getAndIncrement());
                ctx.fireChannelUnregistered();
            }
        });

        childChannel.pipeline().fireUserEventTriggered(new Object());

        // The events should have happened in this order because the inactive and deregistration events
        // get deferred as they do in the AbstractChannel.
        assertEquals(0, exceptionCaught.get());
        assertEquals(1, channelInactive.get());
        assertEquals(2, channelUnregistered.get());
    }

    @Test
    public void callUnsafeCloseMultipleTimes() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        childChannel.unsafe().close(childChannel.newPromise());

        Promise<Void> promise = childChannel.newPromise();
        childChannel.unsafe().close(promise);
        promise.asFuture().syncUninterruptibly();
        childChannel.closeFuture().syncUninterruptibly();
    }

    @Test
    public void endOfStreamDoesNotDiscardData() {
        AtomicInteger numReads = new AtomicInteger(1);
        final AtomicBoolean shouldDisableAutoRead = new AtomicBoolean();
        Consumer<ChannelHandlerContext> ctxConsumer = obj -> {
            if (shouldDisableAutoRead.get()) {
                obj.channel().config().setAutoRead(false);
            }
        };
        LastInboundHandler inboundHandler = new LastInboundHandler(ctxConsumer);
        Http2StreamChannel childChannel = newInboundStream(3, false, numReads, inboundHandler);
        childChannel.config().setAutoRead(false);

        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("1")).stream(childChannel.stream());
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("2")).stream(childChannel.stream());
        Http2DataFrame dataFrame3 = new DefaultHttp2DataFrame(bb("3")).stream(childChannel.stream());
        Http2DataFrame dataFrame4 = new DefaultHttp2DataFrame(bb("4")).stream(childChannel.stream());

        assertEquals(new DefaultHttp2HeadersFrame(request).stream(childChannel.stream()), inboundHandler.readInbound());

        ChannelHandler readCompleteSupressHandler = new ChannelHandler() {
            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                // We want to simulate the parent channel calling channelRead and delay calling channelReadComplete.
            }
        };

        parentChannel.pipeline().addFirst(readCompleteSupressHandler);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("1"), 0, false);

        assertEqualsAndRelease(dataFrame1, inboundHandler.<Http2DataFrame>readInbound());

        // Deliver frames, and then a stream closed while read is inactive.
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("2"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("3"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("4"), 0, false);

        shouldDisableAutoRead.set(true);
        childChannel.config().setAutoRead(true);
        numReads.set(1);

        frameInboundWriter.writeInboundRstStream(childChannel.stream().id(), Http2Error.NO_ERROR.code());

        // Detecting EOS should flush all pending data regardless of read calls.
        assertEqualsAndRelease(dataFrame2, inboundHandler.<Http2DataFrame>readInbound());
        assertNull(inboundHandler.readInbound());

        // As we limited the number to 1 we also need to call read() again.
        childChannel.read();

        assertEqualsAndRelease(dataFrame3, inboundHandler.<Http2DataFrame>readInbound());
        assertEqualsAndRelease(dataFrame4, inboundHandler.<Http2DataFrame>readInbound());

        Http2ResetFrame resetFrame = useUserEventForResetFrame() ? inboundHandler.readUserEvent() :
                inboundHandler.readInbound();

        assertEquals(childChannel.stream(), resetFrame.stream());
        assertEquals(Http2Error.NO_ERROR.code(), resetFrame.errorCode());

        assertNull(inboundHandler.readInbound());

        // Now we want to call channelReadComplete and simulate the end of the read loop.
        parentChannel.pipeline().remove(readCompleteSupressHandler);
        parentChannel.flushInbound();

        childChannel.closeFuture().syncUninterruptibly();
    }

    protected abstract boolean useUserEventForResetFrame();

    protected abstract boolean ignoreWindowUpdateFrames();

    @Test
    public void windowUpdateFrames() {
        AtomicInteger numReads = new AtomicInteger(1);
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, numReads, inboundHandler);

        assertEquals(new DefaultHttp2HeadersFrame(request).stream(childChannel.stream()), inboundHandler.readInbound());

        frameInboundWriter.writeInboundWindowUpdate(childChannel.stream().id(), 4);

        Http2WindowUpdateFrame updateFrame = inboundHandler.readInbound();
        if (ignoreWindowUpdateFrames()) {
            assertNull(updateFrame);
        } else {
            assertEquals(new DefaultHttp2WindowUpdateFrame(4).stream(childChannel.stream()), updateFrame);
        }

        frameInboundWriter.writeInboundWindowUpdate(Http2CodecUtil.CONNECTION_STREAM_ID, 6);

        assertNull(parentChannel.readInbound());
        childChannel.close().syncUninterruptibly();
    }

    @Test
    public void childQueueIsDrainedAndNewDataIsDispatchedInParentReadLoopAutoRead() {
        AtomicInteger numReads = new AtomicInteger(1);
        final AtomicInteger channelReadCompleteCount = new AtomicInteger(0);
        final AtomicBoolean shouldDisableAutoRead = new AtomicBoolean();
        Consumer<ChannelHandlerContext> ctxConsumer = obj -> {
            channelReadCompleteCount.incrementAndGet();
            if (shouldDisableAutoRead.get()) {
                obj.channel().config().setAutoRead(false);
            }
        };
        LastInboundHandler inboundHandler = new LastInboundHandler(ctxConsumer);
        Http2StreamChannel childChannel = newInboundStream(3, false, numReads, inboundHandler);
        childChannel.config().setAutoRead(false);

        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("1")).stream(childChannel.stream());
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("2")).stream(childChannel.stream());
        Http2DataFrame dataFrame3 = new DefaultHttp2DataFrame(bb("3")).stream(childChannel.stream());
        Http2DataFrame dataFrame4 = new DefaultHttp2DataFrame(bb("4")).stream(childChannel.stream());

        assertEquals(new DefaultHttp2HeadersFrame(request).stream(childChannel.stream()), inboundHandler.readInbound());

        ChannelHandler readCompleteSupressHandler = new ChannelHandler() {
            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                // We want to simulate the parent channel calling channelRead and delay calling channelReadComplete.
            }
        };
        parentChannel.pipeline().addFirst(readCompleteSupressHandler);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("1"), 0, false);

        assertEqualsAndRelease(dataFrame1, inboundHandler.<Http2DataFrame>readInbound());

        // We want one item to be in the queue, and allow the numReads to be larger than 1. This will ensure that
        // when beginRead() is called the child channel is added to the readPending queue of the parent channel.
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("2"), 0, false);

        numReads.set(10);
        shouldDisableAutoRead.set(true);
        childChannel.config().setAutoRead(true);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("3"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("4"), 0, false);

        // Detecting EOS should flush all pending data regardless of read calls.
        assertEqualsAndRelease(dataFrame2, inboundHandler.<Http2DataFrame>readInbound());
        assertEqualsAndRelease(dataFrame3, inboundHandler.<Http2DataFrame>readInbound());
        assertEqualsAndRelease(dataFrame4, inboundHandler.<Http2DataFrame>readInbound());

        assertNull(inboundHandler.readInbound());

        // Now we want to call channelReadComplete and simulate the end of the read loop.
        parentChannel.pipeline().remove(readCompleteSupressHandler);
        parentChannel.flushInbound();

        // 3 = 1 for initialization + 1 for read when auto read was off + 1 for when auto read was back on
        assertEquals(3, channelReadCompleteCount.get());
    }

    @Test
    public void childQueueIsDrainedAndNewDataIsDispatchedInParentReadLoopNoAutoRead() {
        final AtomicInteger numReads = new AtomicInteger(1);
        final AtomicInteger channelReadCompleteCount = new AtomicInteger(0);
        final AtomicBoolean shouldDisableAutoRead = new AtomicBoolean();
        Consumer<ChannelHandlerContext> ctxConsumer = obj -> {
            channelReadCompleteCount.incrementAndGet();
            if (shouldDisableAutoRead.get()) {
                obj.channel().config().setAutoRead(false);
            }
        };
        final LastInboundHandler inboundHandler = new LastInboundHandler(ctxConsumer);
        Http2StreamChannel childChannel = newInboundStream(3, false, numReads, inboundHandler);
        childChannel.config().setAutoRead(false);

        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("1")).stream(childChannel.stream());
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("2")).stream(childChannel.stream());
        Http2DataFrame dataFrame3 = new DefaultHttp2DataFrame(bb("3")).stream(childChannel.stream());
        Http2DataFrame dataFrame4 = new DefaultHttp2DataFrame(bb("4")).stream(childChannel.stream());

        assertEquals(new DefaultHttp2HeadersFrame(request).stream(childChannel.stream()), inboundHandler.readInbound());

        ChannelHandler readCompleteSupressHandler = new ChannelHandler() {
            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                // We want to simulate the parent channel calling channelRead and delay calling channelReadComplete.
            }
        };
        parentChannel.pipeline().addFirst(readCompleteSupressHandler);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("1"), 0, false);

        assertEqualsAndRelease(dataFrame1, inboundHandler.readInbound());

        // We want one item to be in the queue, and allow the numReads to be larger than 1. This will ensure that
        // when beginRead() is called the child channel is added to the readPending queue of the parent channel.
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("2"), 0, false);

        numReads.set(2);
        childChannel.read();

        assertEqualsAndRelease(dataFrame2, inboundHandler.readInbound());

        assertNull(inboundHandler.readInbound());

        // This is the second item that was read, this should be the last until we call read() again. This should also
        // notify of readComplete().
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("3"), 0, false);

        assertEqualsAndRelease(dataFrame3, inboundHandler.readInbound());

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("4"), 0, false);
        assertNull(inboundHandler.readInbound());

        childChannel.read();

        assertEqualsAndRelease(dataFrame4, inboundHandler.readInbound());

        assertNull(inboundHandler.readInbound());

        // Now we want to call channelReadComplete and simulate the end of the read loop.
        parentChannel.pipeline().remove(readCompleteSupressHandler);
        parentChannel.flushInbound();

        // 3 = 1 for initialization + 1 for first read of 2 items + 1 for second read of 2 items +
        // 1 for parent channel readComplete
        assertEquals(4, channelReadCompleteCount.get());
    }

    @Test
    public void useReadWithoutAutoReadInRead() {
        useReadWithoutAutoReadBuffered(false);
    }

    @Test
    public void useReadWithoutAutoReadInReadComplete() {
        useReadWithoutAutoReadBuffered(true);
    }

    private void useReadWithoutAutoReadBuffered(final boolean triggerOnReadComplete) {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        assertTrue(childChannel.config().isAutoRead());
        childChannel.config().setAutoRead(false);
        assertFalse(childChannel.config().isAutoRead());

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        // Write some bytes to get the channel into the idle state with buffered data and also verify we
        // do not dispatch it until we receive a read() call.
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, false);

        // Add a handler which will request reads.
        childChannel.pipeline().addFirst(new ChannelHandler() {

            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                ctx.fireChannelReadComplete();
                if (triggerOnReadComplete) {
                    ctx.read();
                    ctx.read();
                }
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.fireChannelRead(msg);
                if (!triggerOnReadComplete) {
                    ctx.read();
                    ctx.read();
                }
            }
        });

        inboundHandler.channel().read();

        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 3);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world2"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo2"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar2"), 0, true);

        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 3);
    }

    private static final class FlushSniffer implements ChannelHandler {

        private boolean didFlush;

        public boolean checkFlush() {
            boolean r = didFlush;
            didFlush = false;
            return r;
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            didFlush = true;
            ctx.flush();
        }
    }

    @Test
    public void windowUpdatesAreFlushed() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        FlushSniffer flushSniffer = new FlushSniffer();
        parentChannel.pipeline().addFirst(flushSniffer);

        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        assertTrue(childChannel.config().isAutoRead());
        childChannel.config().setAutoRead(false);
        assertFalse(childChannel.config().isAutoRead());

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        assertTrue(flushSniffer.checkFlush());

        // Write some bytes to get the channel into the idle state with buffered data and also verify we
        // do not dispatch it until we receive a read() call.
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb(16 * 1024), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb(16 * 1024), 0, false);
        assertTrue(flushSniffer.checkFlush());

        verify(frameWriter, never())
                .writeWindowUpdate(any(ChannelHandlerContext.class), anyInt(), anyInt());
        // only the first one was read because it was legacy auto-read behavior.
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 1);
        assertFalse(flushSniffer.checkFlush());

        // Trigger a read of the second frame.
        childChannel.read();
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 1);
        // We expect a flush here because the StreamChannel will flush the smaller increment but the
        // connection will collect the bytes and decide not to send a wire level frame until more are consumed.
        assertTrue(flushSniffer.checkFlush());
        verify(frameWriter, never())
                .writeWindowUpdate(any(ChannelHandlerContext.class), anyInt(), anyInt());

        // Call read one more time which should trigger the writing of the flow control update.
        childChannel.read();
        verify(frameWriter)
                .writeWindowUpdate(any(ChannelHandlerContext.class), eq(0), eq(32 * 1024));
        verify(frameWriter)
                .writeWindowUpdate(any(ChannelHandlerContext.class), eq(childChannel.stream().id()),
                        eq(32 * 1024));
        assertTrue(flushSniffer.checkFlush());
    }

    private static void verifyFramesMultiplexedToCorrectChannel(Http2StreamChannel streamChannel,
                                                                LastInboundHandler inboundHandler,
                                                                int numFrames) {
        for (int i = 0; i < numFrames; i++) {
            Http2StreamFrame frame = inboundHandler.readInbound();
            assertNotNull(frame, i + " out of " + numFrames + " received");
            assertEquals(streamChannel.stream(), frame.stream());
            release(frame);
        }
        assertNull(inboundHandler.readInbound());
    }

    private static int eqStreamId(Http2StreamChannel channel) {
        return eq(channel.stream().id());
    }
}
