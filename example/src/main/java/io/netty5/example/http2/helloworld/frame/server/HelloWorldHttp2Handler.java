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

package io.netty5.example.http2.helloworld.frame.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandler.Sharable;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty5.handler.codec.http2.DefaultHttp2Headers;
import io.netty5.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty5.handler.codec.http2.DefaultHttp2WindowUpdateFrame;
import io.netty5.handler.codec.http2.Http2DataFrame;
import io.netty5.handler.codec.http2.Http2FrameStream;
import io.netty5.handler.codec.http2.Http2Headers;
import io.netty5.handler.codec.http2.Http2HeadersFrame;
import io.netty5.util.CharsetUtil;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty5.handler.codec.http.HttpResponseStatus.OK;

/**
 * A simple handler that responds with the message "Hello World!".
 *
 * <p>This example is making use of the "frame codec" http2 API. This API is very experimental and incomplete.
 */
@Sharable
public class HelloWorldHttp2Handler implements ChannelHandler {

    static final ByteBuf RESPONSE_BYTES = unreleasableBuffer(
            copiedBuffer("Hello World", CharsetUtil.UTF_8)).asReadOnly();

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.fireExceptionCaught(cause);
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2HeadersFrame) {
            onHeadersRead(ctx, (Http2HeadersFrame) msg);
        } else if (msg instanceof Http2DataFrame) {
            onDataRead(ctx, (Http2DataFrame) msg);
        } else {
           ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    /**
     * If receive a frame with end-of-stream set, send a pre-canned response.
     */
    private static void onDataRead(ChannelHandlerContext ctx, Http2DataFrame data) throws Exception {
        Http2FrameStream stream = data.stream();

        if (data.isEndStream()) {
            sendResponse(ctx, stream, data.content());
        } else {
            // We do not send back the response to the remote-peer, so we need to release it.
            data.release();
        }

        // Update the flowcontroller
        ctx.write(new DefaultHttp2WindowUpdateFrame(data.initialFlowControlledBytes()).stream(stream));
    }

    /**
     * If receive a frame with end-of-stream set, send a pre-canned response.
     */
    private static void onHeadersRead(ChannelHandlerContext ctx, Http2HeadersFrame headers)
            throws Exception {
        if (headers.isEndStream()) {
            ByteBuf content = ctx.alloc().buffer();
            content.writeBytes(RESPONSE_BYTES.duplicate());
            ByteBufUtil.writeAscii(content, " - via HTTP/2");
            sendResponse(ctx, headers.stream(), content);
        }
    }

    /**
     * Sends a "Hello World" DATA frame to the client.
     */
    private static void sendResponse(ChannelHandlerContext ctx, Http2FrameStream stream, ByteBuf payload) {
        // Send a frame for the response status
        Http2Headers headers = new DefaultHttp2Headers().status(OK.codeAsText());
        ctx.write(new DefaultHttp2HeadersFrame(headers).stream(stream));
        ctx.write(new DefaultHttp2DataFrame(payload, true).stream(stream));
    }
}
