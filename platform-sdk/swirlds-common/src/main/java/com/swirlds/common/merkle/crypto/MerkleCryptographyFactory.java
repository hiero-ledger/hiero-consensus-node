// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.crypto;

import com.swirlds.common.merkle.crypto.internal.MerkleCryptoEngine;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.config.CryptoConfig;

/**
 * Builds {@link MerkleCryptography} instances.
 */
public final class MerkleCryptographyFactory {

    private MerkleCryptographyFactory() {}

    /**
     * Create a new merkle crypto engine.
     *
     * @param configuration the configuration
     * @return the new merkle crypto engine
     */
    @NonNull
    public static MerkleCryptography create(@NonNull final Configuration configuration) {
        return new MerkleCryptoEngine(configuration.getConfigData(CryptoConfig.class));
    }
}
