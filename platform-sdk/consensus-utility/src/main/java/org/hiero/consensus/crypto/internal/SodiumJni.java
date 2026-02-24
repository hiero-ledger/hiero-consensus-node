// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto.internal;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;

/**
 * Internal class to hold the JNI interface to the native libSodium library.
 */
final class SodiumJni {
    private SodiumJni() {
        // Prevent instantiation
    }
    /**
     * The JNI interface to the underlying native libSodium dynamic library. This variable is initialized when this
     * class is loaded and initialized by the {@link ClassLoader}.
     */
    static final Sign.Native SODIUM = new LazySodiumJava(new SodiumJava());
}
