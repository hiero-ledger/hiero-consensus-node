// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import com.hedera.cryptography.libsodium.Libsodium;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Ed25519 signature verification shared by the chain verifiers (Sei CometBFT, Canton). Backed by
 * libsodium's {@code crypto_sign_verify_detached}.
 *
 * <p><b>Intentional parity exception:</b> libsodium rejects small-order public keys and small-order or
 * non-canonical {@code R}, and non-canonical {@code S}. The Solidity reference ({@code Ed25519.sol} in
 * clpr-smart-contracts) performs no small-order check, so for example the identity key with {@code R} = identity
 * and {@code S} = 0 verifies there for any message but is rejected here. The stricter behavior is deliberate;
 * see {@code Ed25519VerifyTest#rejectsSmallOrderIdentityKeyThatLaxVerifiersAccept}.
 */
public final class Ed25519Verify {

    /// libsodium is used for Ed25519 signatures.
    private static final Libsodium LIBSODIUM = Libsodium.getInstance();

    /** Length in bytes of an Ed25519 public key. */
    public static final int PUBLIC_KEY_LENGTH = 32;

    /** Length in bytes of an Ed25519 signature. */
    public static final int SIGNATURE_LENGTH = 64;

    private Ed25519Verify() {}

    /**
     * Verifies an Ed25519 signature over the raw {@code message}; returns {@code false} for
     * malformed keys or signatures so callers can treat invalid input as an unsigned vote.
     */
    public static boolean verify(
            @NonNull final byte[] publicKey32, @NonNull final byte[] message, @NonNull final byte[] signature64) {
        Objects.requireNonNull(publicKey32, "publicKey32");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(signature64, "signature64");
        if (publicKey32.length != PUBLIC_KEY_LENGTH || signature64.length != SIGNATURE_LENGTH) {
            return false;
        }
        try {
            return LIBSODIUM.cryptoSignVerifyDetached(
                            MemorySegment.ofArray(signature64),
                            MemorySegment.ofArray(message),
                            message.length,
                            MemorySegment.ofArray(publicKey32))
                    == 0;
        } catch (final RuntimeException e) {
            return false;
        }
    }
}
