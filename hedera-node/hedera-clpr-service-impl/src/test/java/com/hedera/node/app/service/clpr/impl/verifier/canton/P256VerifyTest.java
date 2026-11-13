// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link P256Verify}.
 *
 * <p>All vectors are CONSTRUCTED with the JDK's SunEC provider: there is no real P-256
 * mediator signature in the fixtures (the mediator/sequencer fixtures are Ed25519; the Solidity
 * {@code p256} gas-fixture arrays are synthetic and were not extracted). The construction uses a
 * freshly generated secp256r1 key pair and a real {@code SHA256withECDSA} signature, so the
 * (x, y, digest, r, s) vector is produced by a real signer, not invented bytes.
 */
class P256VerifyTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void setup() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = kpg.generateKeyPair();
    }

    @Test
    void acceptsValidP256SignatureOverDigest() throws Exception {
        final byte[] message = "canton mediator verdict".getBytes();
        final byte[] derSig = sign(message);
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(message);
        final var ec = (ECPublicKey) keyPair.getPublic();
        final byte[] x = to32(ec.getW().getAffineX());
        final byte[] y = to32(ec.getW().getAffineY());
        final var rs = CantonMediators.parseEcdsaSignature(derSig);
        assertThat(rs.ok()).isTrue();

        assertThat(P256Verify.verify(x, y, digest, rs.r(), rs.s())).isTrue();
    }

    @Test
    void rejectsTamperedDigest() throws Exception {
        final byte[] message = "canton mediator verdict".getBytes();
        final byte[] derSig = sign(message);
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(message);
        digest[0] ^= 0x01; // flip a bit
        final var ec = (ECPublicKey) keyPair.getPublic();
        final byte[] x = to32(ec.getW().getAffineX());
        final byte[] y = to32(ec.getW().getAffineY());
        final var rs = CantonMediators.parseEcdsaSignature(derSig);

        assertThat(P256Verify.verify(x, y, digest, rs.r(), rs.s())).isFalse();
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        final byte[] message = "canton mediator verdict".getBytes();
        final byte[] derSig = sign(message);
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(message);
        final var ec = (ECPublicKey) keyPair.getPublic();
        final byte[] x = to32(ec.getW().getAffineX());
        final byte[] y = to32(ec.getW().getAffineY());
        final var rs = CantonMediators.parseEcdsaSignature(derSig);
        final byte[] badR = rs.r().clone();
        badR[31] ^= 0x01;

        assertThat(P256Verify.verify(x, y, digest, badR, rs.s())).isFalse();
    }

    @Test
    void rejectsWrongLengthInputs() {
        final byte[] b31 = new byte[31];
        final byte[] b32 = new byte[32];
        assertThat(P256Verify.verify(b31, b32, b32, b32, b32)).isFalse();
        assertThat(P256Verify.verify(b32, b32, b32, b32, new byte[33])).isFalse();
    }

    @Test
    void rejectsOffCurvePoint() throws Exception {
        final byte[] message = "x".getBytes();
        final byte[] derSig = sign(message);
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(message);
        final var rs = CantonMediators.parseEcdsaSignature(derSig);
        // (1, 1) is not on secp256r1.
        final byte[] x = to32(BigInteger.ONE);
        final byte[] y = to32(BigInteger.ONE);

        assertThat(P256Verify.verify(x, y, digest, rs.r(), rs.s())).isFalse();
    }

    private static byte[] sign(final byte[] message) throws Exception {
        final Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(message);
        return signer.sign();
    }

    private static byte[] to32(final BigInteger v) {
        final byte[] raw = v.toByteArray();
        final byte[] out = new byte[32];
        if (raw.length <= 32) {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        } else {
            // Strip a leading sign byte.
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        }
        return out;
    }
}
