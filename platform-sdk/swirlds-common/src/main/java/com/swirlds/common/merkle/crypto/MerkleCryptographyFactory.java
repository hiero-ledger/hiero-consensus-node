// SPDX-License-Identifier: Apache-2.0
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
