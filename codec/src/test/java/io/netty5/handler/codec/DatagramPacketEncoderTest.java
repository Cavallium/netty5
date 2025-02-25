/*
 * Copyright 2016 The Netty Project
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
package io.netty5.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty5.buffer.api.Resource;
import io.netty5.channel.AddressedEnvelope;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.DefaultAddressedEnvelope;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.channel.socket.DatagramPacket;
import io.netty5.handler.codec.string.StringEncoder;
import io.netty5.util.CharsetUtil;
import io.netty5.util.internal.SocketUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DatagramPacketEncoderTest {

    private EmbeddedChannel channel;

    @BeforeEach
    public void setUp() {
        channel = new EmbeddedChannel(
                new DatagramPacketEncoder<String>(
                        new StringEncoder(CharsetUtil.UTF_8)));
    }

    @AfterEach
    public void tearDown() {
        assertFalse(channel.finish());
    }

    @Test
    public void testEncode() {
        testEncode(false);
    }

    @Test
    public void testEncodeWithSenderIsNull() {
        testEncode(true);
    }

    private void testEncode(boolean senderIsNull) {
        InetSocketAddress recipient = SocketUtils.socketAddress("127.0.0.1", 10000);
        InetSocketAddress sender = senderIsNull ? null : SocketUtils.socketAddress("127.0.0.1", 20000);
        assertTrue(channel.writeOutbound(
                new DefaultAddressedEnvelope<>("netty", recipient, sender)));
        DatagramPacket packet = channel.readOutbound();
        try {
            assertEquals("netty", packet.content().toString(CharsetUtil.UTF_8));
            assertEquals(recipient, packet.recipient());
            assertEquals(sender, packet.sender());
        } finally {
            packet.release();
        }
    }

    @Test
    public void testUnmatchedMessageType() {
        InetSocketAddress recipient = SocketUtils.socketAddress("127.0.0.1", 10000);
        InetSocketAddress sender = SocketUtils.socketAddress("127.0.0.1", 20000);
        DefaultAddressedEnvelope<Long, InetSocketAddress> envelope =
                new DefaultAddressedEnvelope<>(1L, recipient, sender);
        assertTrue(channel.writeOutbound(envelope));
        DefaultAddressedEnvelope<Long, InetSocketAddress> output = channel.readOutbound();
        assertSame(envelope, output);
    }

    @Test
    public void testUnmatchedType() {
        String netty = "netty";
        assertTrue(channel.writeOutbound(netty));
        assertSame(netty, channel.readOutbound());
    }

    @Test
    public void testIsNotSharable() {
        testSharable(false);
    }

    @Test
    public void testIsSharable() {
        testSharable(true);
    }

    private static void testSharable(boolean sharable) {
        MessageToMessageEncoder<AddressedEnvelope<ByteBuf, InetSocketAddress>> wrapped =
                new TestMessageToMessageEncoder(sharable);

        DatagramPacketEncoder<AddressedEnvelope<ByteBuf, InetSocketAddress>> encoder =
                new DatagramPacketEncoder<>(wrapped);
        assertEquals(wrapped.isSharable(), encoder.isSharable());
    }

    private static final class TestMessageToMessageEncoder
            extends MessageToMessageEncoder<AddressedEnvelope<ByteBuf, InetSocketAddress>> {

        private final boolean sharable;

        TestMessageToMessageEncoder(boolean sharable) {
            this.sharable = sharable;
        }

        @Override
        protected void encode(
                ChannelHandlerContext ctx, AddressedEnvelope<ByteBuf,
                InetSocketAddress> msg, List<Object> out) {
            // NOOP
        }

        @Override
        public boolean isSharable() {
            return sharable;
        }
    }
}
