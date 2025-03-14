// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto;

import com.swirlds.common.crypto.engine.CryptoEngine;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides a singleton {@link Cryptography} instance.
 */
public final class CryptographyProvider {
    private static final Cryptography INSTANCE = new CryptoEngine();

    private CryptographyProvider() {}

    /**
     * Returns the singleton {@link Cryptography} instance.
     *
     * @return the singleton {@link Cryptography} instance
     */
    @NonNull
    public static Cryptography getInstance() {
        return INSTANCE;
    }
}
