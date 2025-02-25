/*
 * Copyright 2019 The Netty Project
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
package io.netty5.testsuite.transport.socket;

import io.netty5.bootstrap.Bootstrap;
import io.netty5.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty5.buffer.api.Buffer;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelInitializer;
import io.netty5.channel.ChannelOption;
import io.netty5.testsuite.transport.TestsuitePermutation;
import io.netty5.util.concurrent.FutureListener;
import io.netty5.util.concurrent.ImmediateEventExecutor;
import io.netty5.util.concurrent.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty5.buffer.api.DefaultBufferAllocators.preferredAllocator;
import static io.netty5.util.CharsetUtil.US_ASCII;

public abstract class AbstractSocketReuseFdTest extends AbstractSocketTest {
    @Override
    protected abstract SocketAddress newSocketAddress();

    @Override
    protected abstract List<TestsuitePermutation.BootstrapComboFactory<ServerBootstrap, Bootstrap>> newFactories();

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS)
    public void testReuseFdByteBuf(TestInfo testInfo) throws Throwable {
        run(testInfo, (sb, cb) -> testReuseFd(sb, cb, false));
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS)
    public void testReuseFd(TestInfo testInfo) throws Throwable {
        run(testInfo, (sb, cb) -> testReuseFd(sb, cb, true));
    }

    public void testReuseFd(ServerBootstrap sb, Bootstrap cb, boolean newBufferAPI) throws Throwable {
        sb.childOption(ChannelOption.AUTO_READ, true);
        cb.option(ChannelOption.AUTO_READ, true);

        // Use a number which will typically not exceed /proc/sys/net/core/somaxconn (which is 128 on linux by default
        // often).
        int numChannels = 100;
        final AtomicReference<Throwable> globalException = new AtomicReference<Throwable>();
        final AtomicInteger serverRemaining = new AtomicInteger(numChannels);
        final AtomicInteger clientRemaining = new AtomicInteger(numChannels);
        final Promise<Void> serverDonePromise = ImmediateEventExecutor.INSTANCE.newPromise();
        final Promise<Void> clientDonePromise = ImmediateEventExecutor.INSTANCE.newPromise();

        sb.childHandler(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(Channel sch) {
                ReuseFdHandler sh = new ReuseFdHandler(
                    false,
                    globalException,
                    serverRemaining,
                    serverDonePromise,
                    newBufferAPI);
                sch.pipeline().addLast("handler", sh);
            }
        });

        cb.handler(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(Channel sch) {
                ReuseFdHandler ch = new ReuseFdHandler(
                        true,
                        globalException,
                        clientRemaining,
                        clientDonePromise,
                        newBufferAPI);
                sch.pipeline().addLast("handler", ch);
            }
        });

        FutureListener<Channel> listener = future -> {
            if (future.isFailed()) {
                clientDonePromise.tryFailure(future.cause());
            }
        };

        Channel sc = sb.bind().get();
        for (int i = 0; i < numChannels; i++) {
            cb.connect(sc.localAddress()).addListener(listener);
        }

        clientDonePromise.asFuture().sync();
        serverDonePromise.asFuture().sync();
        sc.close().sync();

        if (globalException.get() != null && !(globalException.get() instanceof IOException)) {
            throw globalException.get();
        }
    }

    static class ReuseFdHandler implements ChannelHandler {
        private static final String EXPECTED_PAYLOAD = "payload";

        private final Promise<Void> donePromise;
        private final boolean newBufferAPI;
        private final AtomicInteger remaining;
        private final boolean client;
        volatile Channel channel;
        final AtomicReference<Throwable> globalException;
        final AtomicReference<Throwable> exception = new AtomicReference<Throwable>();
        final StringBuilder received = new StringBuilder();

        ReuseFdHandler(
                boolean client,
                AtomicReference<Throwable> globalException,
                AtomicInteger remaining,
                Promise<Void> donePromise,
                boolean newBufferAPI) {
            this.client = client;
            this.globalException = globalException;
            this.remaining = remaining;
            this.donePromise = donePromise;
            this.newBufferAPI = newBufferAPI;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            channel = ctx.channel();
            if (client) {
                if (newBufferAPI) {
                    ctx.writeAndFlush(preferredAllocator().copyOf(EXPECTED_PAYLOAD.getBytes(US_ASCII)));
                } else {
                    ctx.writeAndFlush(Unpooled.copiedBuffer(EXPECTED_PAYLOAD, US_ASCII));
                }
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Buffer) {
                Buffer buf = (Buffer) msg;
                received.append(buf.toString(US_ASCII));
                buf.close();

                if (received.toString().equals(EXPECTED_PAYLOAD)) {
                    if (client) {
                        ctx.close();
                    } else {
                        ctx.writeAndFlush(preferredAllocator().copyOf(EXPECTED_PAYLOAD.getBytes(US_ASCII)));
                    }
                }
            } else if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                received.append(buf.toString(US_ASCII));
                buf.release();

                if (received.toString().equals(EXPECTED_PAYLOAD)) {
                    if (client) {
                        ctx.close();
                    } else {
                        ctx.writeAndFlush(Unpooled.copiedBuffer(EXPECTED_PAYLOAD, US_ASCII));
                    }
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (exception.compareAndSet(null, cause)) {
                donePromise.tryFailure(new IllegalStateException("exceptionCaught: " + ctx.channel(), cause));
                ctx.close();
            }
            globalException.compareAndSet(null, cause);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (remaining.decrementAndGet() == 0) {
                if (received.toString().equals(EXPECTED_PAYLOAD)) {
                    donePromise.setSuccess(null);
                } else {
                    donePromise.tryFailure(new Exception("Unexpected payload:" + received));
                }
            }
        }
    }
}
