/*
 * Copyright 2012 The Netty Project
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
package io.netty5.handler.codec.base64;

import static java.util.Objects.requireNonNull;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty5.channel.ChannelHandler.Sharable;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelPipeline;
import io.netty5.handler.codec.DelimiterBasedFrameDecoder;
import io.netty5.handler.codec.Delimiters;
import io.netty5.handler.codec.MessageToMessageEncoder;

/**
 * Encodes a {@link ByteBuf} into a Base64-encoded {@link ByteBuf}.
 * A typical setup for TCP/IP would be:
 * <pre>
 * {@link ChannelPipeline} pipeline = ...;
 *
 * // Decoders
 * pipeline.addLast("frameDecoder", new {@link DelimiterBasedFrameDecoder}(80, {@link Delimiters#nulDelimiter()}));
 * pipeline.addLast("base64Decoder", new {@link Base64Decoder}());
 *
 * // Encoder
 * pipeline.addLast("base64Encoder", new {@link Base64Encoder}());
 * </pre>
 */
@Sharable
public class Base64Encoder extends MessageToMessageEncoder<ByteBuf> {

    private final boolean breakLines;
    private final Base64Dialect dialect;

    public Base64Encoder() {
        this(true);
    }

    public Base64Encoder(boolean breakLines) {
        this(breakLines, Base64Dialect.STANDARD);
    }

    public Base64Encoder(boolean breakLines, Base64Dialect dialect) {
        requireNonNull(dialect, "dialect");

        this.breakLines = breakLines;
        this.dialect = dialect;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        out.add(Base64.encode(msg, msg.readerIndex(), msg.readableBytes(), breakLines, dialect));
    }
}
