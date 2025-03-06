// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto;

import com.swirlds.common.crypto.engine.CryptoEngine;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Builds {@link Cryptography} instances.
 */
public final class CryptographyFactory {

    private CryptographyFactory() {}

    /**
     * Creates a new {@link Cryptography} instance using the given configuration.
     *
     * @return a new {@link Cryptography} instance
     */
    @NonNull
    public static Cryptography create() {
        return new CryptoEngine();
    }
}
