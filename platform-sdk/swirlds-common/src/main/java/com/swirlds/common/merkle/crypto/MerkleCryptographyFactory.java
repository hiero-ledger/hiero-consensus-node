/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.common.merkle.crypto;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.config.CryptoConfig;
import com.swirlds.common.merkle.crypto.internal.MerkleCryptoEngine;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Builds {@link MerkleCryptography} instances.
 */
public final class MerkleCryptographyFactory {

    private MerkleCryptographyFactory() {}

    /**
     * Create a new merkle crypto engine.
     *
     * @param configuration the configuration
     * @param cryptography  the cryptography
     * @return the new merkle crypto engine
     */
    @NonNull
    public static MerkleCryptography create(
            @NonNull final Configuration configuration, @NonNull final Cryptography cryptography) {
        return new MerkleCryptoEngine(cryptography, configuration.getConfigData(CryptoConfig.class));
    }
}
