// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.cryptography.libsodium.Libsodium;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Ed25519VerifyTest {
    /// libsodium is used for Ed25519 signatures.
    private static final Libsodium LIBSODIUM = Libsodium.getInstance();

    private static final byte[] MESSAGE = "message".getBytes(StandardCharsets.UTF_8);

    @Test
    void malformedInputsReturnFalse() {
        assertThat(Ed25519Verify.verify(new byte[31], new byte[] {1}, new byte[64]))
                .isFalse();
        assertThat(Ed25519Verify.verify(new byte[32], new byte[] {1}, new byte[63]))
                .isFalse();
    }

    @Test
    void invalidRandomInputsReturnFalse() {
        assertThat(Ed25519Verify.verify(new byte[32], new byte[] {1}, new byte[64]))
                .isFalse();
    }

    @Test
    void verifiesRealSignature() {
        // A signature originally produced with Bouncy Castle was confirmed to verify with this libsodium-backed
        // implementation; the test now signs with libsodium itself to avoid the Bouncy Castle dependency.
        final byte[] publicKey = new byte[32];
        final byte[] privateKey = new byte[64];
        keypair(publicKey, privateKey);

        assertThat(Ed25519Verify.verify(publicKey, MESSAGE, sign(privateKey, MESSAGE)))
                .isTrue();
    }

    @Test
    void rejectsTamperedMessageSignatureAndWrongKey() {
        final byte[] publicKey = new byte[32];
        final byte[] privateKey = new byte[64];
        keypair(publicKey, privateKey);
        final byte[] otherPublicKey = new byte[32];
        keypair(otherPublicKey, new byte[64]);
        final byte[] signature = sign(privateKey, MESSAGE);
        assertThat(Ed25519Verify.verify(publicKey, MESSAGE, signature)).isTrue();

        final byte[] tamperedMessage = MESSAGE.clone();
        tamperedMessage[0] ^= 0x01;
        assertThat(Ed25519Verify.verify(publicKey, tamperedMessage, signature)).isFalse();

        final byte[] tamperedSignature = signature.clone();
        tamperedSignature[0] ^= 0x01;
        assertThat(Ed25519Verify.verify(publicKey, MESSAGE, tamperedSignature)).isFalse();

        assertThat(Ed25519Verify.verify(otherPublicKey, MESSAGE, signature)).isFalse();
    }

    /**
     * Differential vector for the validation-profile gap with the Solidity reference, which
     * performs no small-order check: it only rejects {@code S >= L} and compares the re-encoded
     * {@code [S]B - [h]A} with {@code R} byte-for-byte). With A = R = the identity point and S = 0,
     * that equation holds for every message, so a lax verifier accepts it; libsodium rejects
     * small-order public keys and R values, so this implementation does not.
     */
    @Test
    void rejectsSmallOrderIdentityKey() {
        final byte[] identity = new byte[32];
        identity[0] = 0x01; // y = 1, x = 0: the neutral element, order 1
        final byte[] signature = new byte[64];
        System.arraycopy(identity, 0, signature, 0, 32); // R = identity, S = 0

        assertThat(Ed25519Verify.verify(identity, MESSAGE, signature)).isFalse();
        assertThat(Ed25519Verify.verify(identity, new byte[0], signature)).isFalse();
    }

    /**
     * Non-canonical encodings of the identity point: y = p + 1 (p = 2^255 - 19) is the same point as y = 1 but
     * with an unreduced y. The Solidity reference masks the sign bit without requiring y < p, so it decodes this
     * as the identity; libsodium must reject it.
     */
    @Test
    void rejectsNonCanonicalYEncodingOfIdentity() {
        final byte[] nonCanonicalIdentity = new byte[32];
        java.util.Arrays.fill(nonCanonicalIdentity, (byte) 0xFF);
        nonCanonicalIdentity[0] = (byte) 0xEE; // little-endian 2^255 - 18 = p + 1
        nonCanonicalIdentity[31] = 0x7F;
        final byte[] canonicalIdentity = new byte[32];
        canonicalIdentity[0] = 0x01;

        // non-canonical A, canonical R, S = 0
        final byte[] sigCanonicalR = new byte[64];
        System.arraycopy(canonicalIdentity, 0, sigCanonicalR, 0, 32);
        assertThat(Ed25519Verify.verify(nonCanonicalIdentity, MESSAGE, sigCanonicalR))
                .isFalse();

        // non-canonical R, non-canonical A, S = 0
        final byte[] sigNonCanonicalR = new byte[64];
        System.arraycopy(nonCanonicalIdentity, 0, sigNonCanonicalR, 0, 32);
        assertThat(Ed25519Verify.verify(nonCanonicalIdentity, MESSAGE, sigNonCanonicalR))
                .isFalse();
    }

    @Test
    void rejectsNullInputs() {
        assertThatNullPointerException().isThrownBy(() -> Ed25519Verify.verify(null, new byte[0], new byte[64]));
        assertThatNullPointerException().isThrownBy(() -> Ed25519Verify.verify(new byte[32], null, new byte[64]));
        assertThatNullPointerException().isThrownBy(() -> Ed25519Verify.verify(new byte[32], new byte[0], null));
    }

    private static void keypair(final byte[] publicKey, final byte[] privateKey) {
        assertEquals(
                0, LIBSODIUM.cryptoSignKeypair(MemorySegment.ofArray(publicKey), MemorySegment.ofArray(privateKey)));
    }

    private static byte[] sign(final byte[] privateKey, final byte[] message) {
        final byte[] signature = new byte[64];
        assertEquals(
                0,
                LIBSODIUM.cryptoSignDetached(
                        MemorySegment.ofArray(signature),
                        MemorySegment.ofArray(new long[] {signature.length}),
                        MemorySegment.ofArray(message),
                        message.length,
                        MemorySegment.ofArray(privateKey)));
        return signature;
    }
}
