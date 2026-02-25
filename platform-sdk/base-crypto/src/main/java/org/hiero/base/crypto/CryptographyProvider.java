// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.engine.CryptoEngine;

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
