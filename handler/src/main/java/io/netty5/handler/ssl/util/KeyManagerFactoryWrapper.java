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
package io.netty5.handler.ssl.util;

import java.security.KeyStore;
import java.util.Objects;
import javax.net.ssl.KeyManager;
import javax.net.ssl.ManagerFactoryParameters;

public final class KeyManagerFactoryWrapper extends SimpleKeyManagerFactory {
    private final KeyManager km;

    public KeyManagerFactoryWrapper(KeyManager km) {
        this.km = Objects.requireNonNull(km, "km");
    }

    @Override
    protected void engineInit(KeyStore keyStore, char[] var2) throws Exception { }

    @Override
    protected void engineInit(ManagerFactoryParameters managerFactoryParameters)
            throws Exception { }

    @Override
    protected KeyManager[] engineGetKeyManagers() {
        return new KeyManager[] {km};
    }
}
