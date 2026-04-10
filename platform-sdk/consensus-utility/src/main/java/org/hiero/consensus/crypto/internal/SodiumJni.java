// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto.internal;

import org.hiero.base.crypto.engine.LibSodiumEd25519;

/**
 * Internal class to hold the interface to the native libSodium library.
 */
final class SodiumJni {
    private SodiumJni() {
        // Prevent instantiation
    }
    /**
     * The interface to the underlying native libSodium dynamic library via Panama FFM.
     */
    static final LibSodiumEd25519 SODIUM = LibSodiumEd25519.INSTANCE;
}
