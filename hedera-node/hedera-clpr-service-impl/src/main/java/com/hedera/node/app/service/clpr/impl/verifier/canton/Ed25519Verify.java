// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import com.hedera.cryptography.libsodium.Libsodium;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Ed25519 signature verification via libsodium, mirroring the {@code IEd25519Verifier} the reference
 * Solidity verifier delegates to. Canton mediator and sequencer keys of key-spec {@code ED25519}
 * sign the raw 34-byte multihash message directly (no pre-hashing).
 *
 * <p>Like {@link P256Verify}, malformed keys or signatures return {@code false} rather than throwing,
 * so a bad signature can never short-circuit higher-level verification.
 */
public final class Ed25519Verify {

    private static final Libsodium LIBSODIUM = Libsodium.getInstance();

    /** Byte length of an Ed25519 public key. */
    private static final int PUBLIC_KEY_LENGTH = 32;

    /** Byte length of an Ed25519 signature. */
    private static final int SIGNATURE_LENGTH = 64;

    private Ed25519Verify() {}

    /**
     * Verifies that {@code signature} is a valid Ed25519 signature of {@code message} under the
     * 32-byte public key {@code key32}. Returns {@code false} (never throws) for a wrong-length key
     * or signature, or any libsodium failure.
     *
     * @param key32 the 32-byte Ed25519 public key
     * @param message the signed message (verified verbatim; not pre-hashed)
     * @param signature the 64-byte Ed25519 signature
     */
    public static boolean verify(
            @NonNull final byte[] key32, @NonNull final byte[] message, @NonNull final byte[] signature) {
        Objects.requireNonNull(key32, "key32");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(signature, "signature");
        if (key32.length != PUBLIC_KEY_LENGTH || signature.length != SIGNATURE_LENGTH) {
            return false;
        }
        try {
            return LIBSODIUM.cryptoSignVerifyDetached(
                            MemorySegment.ofArray(signature),
                            MemorySegment.ofArray(message),
                            message.length,
                            MemorySegment.ofArray(key32))
                    == 0;
        } catch (final RuntimeException e) {
            return false;
        }
    }
}
