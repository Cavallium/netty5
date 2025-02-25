/*
 * Copyright 2017 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.handler.ssl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.ByteToMessageDecoder;
import io.netty5.handler.codec.DecoderException;
import io.netty5.util.concurrent.Future;
import io.netty5.util.internal.PlatformDependent;
import io.netty5.util.internal.logging.InternalLogger;
import io.netty5.util.internal.logging.InternalLoggerFactory;

/**
 * {@link ByteToMessageDecoder} which allows to be notified once a full {@code ClientHello} was received.
 */
public abstract class SslClientHelloHandler<T> extends ByteToMessageDecoder {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SslClientHelloHandler.class);

    private boolean handshakeFailed;
    private boolean suppressRead;
    private boolean readPending;
    private ByteBuf handshakeBuffer;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        if (!suppressRead && !handshakeFailed) {
            try {
                int readerIndex = in.readerIndex();
                int readableBytes = in.readableBytes();
                int handshakeLength = -1;

                // Check if we have enough data to determine the record type and length.
                while (readableBytes >= SslUtils.SSL_RECORD_HEADER_LENGTH) {
                    final int contentType = in.getUnsignedByte(readerIndex);
                    switch (contentType) {
                        case SslUtils.SSL_CONTENT_TYPE_CHANGE_CIPHER_SPEC:
                            // fall-through
                        case SslUtils.SSL_CONTENT_TYPE_ALERT:
                            final int len = SslUtils.getEncryptedPacketLength(in, readerIndex);

                            // Not an SSL/TLS packet
                            if (len == SslUtils.NOT_ENCRYPTED) {
                                handshakeFailed = true;
                                NotSslRecordException e = new NotSslRecordException(
                                        "not an SSL/TLS record: " + ByteBufUtil.hexDump(in));
                                in.skipBytes(in.readableBytes());
                                ctx.fireUserEventTriggered(new SniCompletionEvent(e));
                                ctx.fireUserEventTriggered(new SslHandshakeCompletionEvent(e));
                                throw e;
                            }
                            if (len == SslUtils.NOT_ENOUGH_DATA) {
                                // Not enough data
                                return;
                            }
                            // No ClientHello
                            select(ctx, null);
                            return;
                        case SslUtils.SSL_CONTENT_TYPE_HANDSHAKE:
                            final int majorVersion = in.getUnsignedByte(readerIndex + 1);
                            // SSLv3 or TLS
                            if (majorVersion == 3) {
                                int packetLength = in.getUnsignedShort(readerIndex + 3) +
                                        SslUtils.SSL_RECORD_HEADER_LENGTH;

                                if (readableBytes < packetLength) {
                                    // client hello incomplete; try again to decode once more data is ready.
                                    return;
                                }
                                if (packetLength == SslUtils.SSL_RECORD_HEADER_LENGTH) {
                                    select(ctx, null);
                                    return;
                                }

                                final int endOffset = readerIndex + packetLength;

                                // Let's check if we already parsed the handshake length or not.
                                if (handshakeLength == -1) {
                                    if (readerIndex + 4 > endOffset) {
                                        // Need more data to read HandshakeType and handshakeLength (4 bytes)
                                        return;
                                    }

                                    final int handshakeType = in.getUnsignedByte(readerIndex +
                                            SslUtils.SSL_RECORD_HEADER_LENGTH);

                                    // Check if this is a clientHello(1)
                                    // See https://tools.ietf.org/html/rfc5246#section-7.4
                                    if (handshakeType != 1) {
                                        select(ctx, null);
                                        return;
                                    }

                                    // Read the length of the handshake as it may arrive in fragments
                                    // See https://tools.ietf.org/html/rfc5246#section-7.4
                                    handshakeLength = in.getUnsignedMedium(readerIndex +
                                            SslUtils.SSL_RECORD_HEADER_LENGTH + 1);

                                    // Consume handshakeType and handshakeLength (this sums up as 4 bytes)
                                    readerIndex += 4;
                                    packetLength -= 4;

                                    if (handshakeLength + 4 + SslUtils.SSL_RECORD_HEADER_LENGTH <= packetLength) {
                                        // We have everything we need in one packet.
                                        // Skip the record header
                                        readerIndex += SslUtils.SSL_RECORD_HEADER_LENGTH;
                                        select(ctx, in.retainedSlice(readerIndex, handshakeLength));
                                        return;
                                    } else {
                                        if (handshakeBuffer == null) {
                                            handshakeBuffer = ctx.alloc().buffer(handshakeLength);
                                        } else {
                                            // Clear the buffer so we can aggregate into it again.
                                            handshakeBuffer.clear();
                                        }
                                    }
                                }

                                // Combine the encapsulated data in one buffer but not include the SSL_RECORD_HEADER
                                handshakeBuffer.writeBytes(in, readerIndex + SslUtils.SSL_RECORD_HEADER_LENGTH,
                                        packetLength - SslUtils.SSL_RECORD_HEADER_LENGTH);
                                readerIndex += packetLength;
                                readableBytes -= packetLength;
                                if (handshakeLength <= handshakeBuffer.readableBytes()) {
                                    ByteBuf clientHello = handshakeBuffer.setIndex(0, handshakeLength);
                                    handshakeBuffer = null;

                                    select(ctx, clientHello);
                                    return;
                                }
                                break;
                            }
                            // fall-through
                        default:
                            // not tls, ssl or application data
                            select(ctx, null);
                            return;
                    }
                }
            } catch (NotSslRecordException e) {
                // Just rethrow as in this case we also closed the channel and this is consistent with SslHandler.
                throw e;
            } catch (Exception e) {
                // unexpected encoding, ignore sni and use default
                if (logger.isDebugEnabled()) {
                    logger.debug("Unexpected client hello packet: " + ByteBufUtil.hexDump(in), e);
                }
                select(ctx, null);
            }
        }
    }

    private void releaseHandshakeBuffer() {
        releaseIfNotNull(handshakeBuffer);
        handshakeBuffer = null;
    }

    private static void releaseIfNotNull(ByteBuf buffer) {
        if (buffer != null) {
            buffer.release();
        }
    }

    private void select(final ChannelHandlerContext ctx, ByteBuf clientHello) {
        final Future<T> future;
        try {
            future = lookup(ctx, clientHello);
            if (future.isDone()) {
                onLookupComplete(ctx, future);
            } else {
                suppressRead = true;
                final ByteBuf finalClientHello = clientHello;
                future.addListener(f -> {
                    releaseIfNotNull(finalClientHello);
                    try {
                        suppressRead = false;
                        try {
                            onLookupComplete(ctx, f);
                        } catch (DecoderException err) {
                            ctx.fireExceptionCaught(err);
                        } catch (Exception cause) {
                            ctx.fireExceptionCaught(new DecoderException(cause));
                        } catch (Throwable cause) {
                            ctx.fireExceptionCaught(cause);
                        }
                    } finally {
                        if (readPending) {
                            readPending = false;
                            ctx.read();
                        }
                    }
                });

                // Ownership was transferred to the FutureListener.
                clientHello = null;
            }
        } catch (Throwable cause) {
            PlatformDependent.throwException(cause);
        } finally {
            releaseIfNotNull(clientHello);
        }
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
        releaseHandshakeBuffer();

        super.handlerRemoved0(ctx);
    }

    /**
     * Kicks off a lookup for the given {@code ClientHello} and returns a {@link Future} which in turn will
     * notify the {@link #onLookupComplete(ChannelHandlerContext, Future)} on completion.
     *
     * See https://tools.ietf.org/html/rfc5246#section-7.4.1.2
     *
     * <pre>
     * struct {
     *    ProtocolVersion client_version;
     *    Random random;
     *    SessionID session_id;
     *    CipherSuite cipher_suites<2..2^16-2>;
     *    CompressionMethod compression_methods<1..2^8-1>;
     *    select (extensions_present) {
     *        case false:
     *            struct {};
     *        case true:
     *            Extension extensions<0..2^16-1>;
     *    };
     * } ClientHello;
     * </pre>
     *
     * @see #onLookupComplete(ChannelHandlerContext, Future)
     */
    protected abstract Future<T> lookup(ChannelHandlerContext ctx, ByteBuf clientHello) throws Exception;

    /**
     * Called upon completion of the {@link #lookup(ChannelHandlerContext, ByteBuf)} {@link Future}.
     *
     * @see #lookup(ChannelHandlerContext, ByteBuf)
     */
    protected abstract void onLookupComplete(ChannelHandlerContext ctx, Future<? extends T> future) throws Exception;

    @Override
    public void read(ChannelHandlerContext ctx) {
        if (suppressRead) {
            readPending = true;
        } else {
            ctx.read();
        }
    }
}
