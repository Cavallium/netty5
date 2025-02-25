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
package io.netty5.handler.ssl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;

/**
 * A marker interface for PEM encoded values.
 */
interface PemEncoded extends ByteBufHolder {

    /**
     * Returns {@code true} if the PEM encoded value is considered
     * sensitive information such as a private key.
     */
    boolean isSensitive();

    @Override
    PemEncoded copy();

    @Override
    PemEncoded duplicate();

    @Override
    PemEncoded retainedDuplicate();

    @Override
    PemEncoded replace(ByteBuf content);

    @Override
    PemEncoded retain();

    @Override
    PemEncoded retain(int increment);

    @Override
    PemEncoded touch();

    @Override
    PemEncoded touch(Object hint);
}
