/*
 * Copyright 2022 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty5.handler.ssl;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Configuration for TLS1.3 certificate compression extension.
 */
public final class OpenSslCertificateCompressionConfig implements
        Iterable<OpenSslCertificateCompressionConfig.AlgorithmConfig> {
    private final List<AlgorithmConfig> pairList;

    private OpenSslCertificateCompressionConfig(AlgorithmConfig... pairs) {
        pairList = List.of(pairs);
    }

    @Override
    public Iterator<AlgorithmConfig> iterator() {
        return pairList.iterator();
    }

    /**
     * Creates a new {@link Builder} for a config.
     *
     * @return a builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder for an {@link OpenSslCertificateCompressionAlgorithm}.
     */
    public static final class Builder {
        private final List<AlgorithmConfig> algorithmList = new ArrayList<>();

        private Builder() { }

        /**
         * Adds a certificate compression algorithm.
         * For servers, algorithm preference order is dictated by the order of algorithm registration.
         * Most preferred algorithm should be registered first.
         *
         * @param algorithm implementation of the compression and or decompression algorithm as a
         * {@link OpenSslCertificateCompressionAlgorithm}
         * @param mode indicates whether decompression support should be advertized, compression should be applied
         *                  for peers which support it, or both. This allows the caller to support one way compression
         *                  only.
         * @return self.
         */
        public Builder addAlgorithm(OpenSslCertificateCompressionAlgorithm algorithm, AlgorithmMode mode) {
            algorithmList.add(new AlgorithmConfig(algorithm, mode));
            return this;
        }

        /**
         * Build a new {@link OpenSslCertificateCompressionConfig} based on the previous
         * added {@link OpenSslCertificateCompressionAlgorithm}s.
         *
         * @return a new config.
         */
        public OpenSslCertificateCompressionConfig build() {
            return new OpenSslCertificateCompressionConfig(algorithmList.toArray(AlgorithmConfig[]::new));
        }
    }

    /**
     * The configuration for the algorithm.
     */
    public static final class AlgorithmConfig {
        private final OpenSslCertificateCompressionAlgorithm algorithm;
        private final AlgorithmMode mode;

        private AlgorithmConfig(OpenSslCertificateCompressionAlgorithm algorithm, AlgorithmMode mode) {
            this.algorithm = requireNonNull(algorithm, "algorithm");
            this.mode = requireNonNull(mode, "mode");
        }

        /**
         * The {@link AlgorithmMode}
         *
         * @return the usage mode.
         */
        public AlgorithmMode mode() {
            return mode;
        }

        /**
         * The configured {@link OpenSslCertificateCompressionAlgorithm}.
         *
         * @return the algorithm
         */
        public OpenSslCertificateCompressionAlgorithm algorithm() {
            return algorithm;
        }
    }

    /**
     * The usage mode of the {@link OpenSslCertificateCompressionAlgorithm}.
     */
    public enum AlgorithmMode {
        /**
         * Compression supported and should be advertised.
         */
        Compress,

        /**
         * Decompression supported and should be advertised.
         */
        Decompress,

        /**
         * Compression and Decompression are supported and both should be advertised.
         */
        Both
    }
}
