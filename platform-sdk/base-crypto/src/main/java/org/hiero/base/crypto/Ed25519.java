// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import com.hedera.cryptography.libsodium.Libsodium;
import java.lang.foreign.MemorySegment;

/// Utility for Ed25519 signatures.
/// All operations call into the native libsodium library.
/// Throws Ed25519Exception when the library functions return an error code.
public final class Ed25519 {
    private static final Libsodium LIBSODIUM = Libsodium.getInstance();

    /// Public key length.
    public static final int PUBLIC_KEY_BYTES = Libsodium.ED25519_PUBLICKEYBYTES;
    /// Secret key length.
    public static final int SECRET_KEY_BYTES = Libsodium.ED25519_SECRETKEYBYTES;
    /// Signature length.
    public static final int SIGNATURE_BYTES = Libsodium.ED25519_BYTES;
    /// Keypair seed length.
    public static final int SEED_BYTES = Libsodium.ED25519_SEEDBYTES;

    /// An Ed25519 exception.
    public static final class Ed25519Exception extends RuntimeException {
        public Ed25519Exception(String message) {
            super(message);
        }
    }

    private Ed25519() {}

    /// Generate 32 bytes publicKey and 64 bytes secretKey from a 32 bytes seed.
    public static void newKeypair(byte[] publicKey, byte[] secretKey, byte[] seed) {
        final int ret = LIBSODIUM.cryptoSignSeedKeypair(
                MemorySegment.ofArray(publicKey), MemorySegment.ofArray(secretKey), MemorySegment.ofArray(seed));
        if (ret != 0) {
            throw new Ed25519Exception("LIBSODIUM.cryptoSignSeedKeypair returned non-zero " + ret);
        }
    }

    /// Produces 64 bytes signature for a given message using 64 bytes secret key.
    public static void sign(byte[] signature, byte[] message, byte[] secretKey) {
        final long[] sigLen = new long[] {signature.length};
        final int ret = LIBSODIUM.cryptoSignDetached(
                MemorySegment.ofArray(signature),
                MemorySegment.ofArray(sigLen),
                MemorySegment.ofArray(message),
                message.length,
                MemorySegment.ofArray(secretKey));
        if (ret != 0) {
            throw new RuntimeException("LIBSODIUM.cryptoSignDetached returned non-zero " + ret);
        }
    }
}
