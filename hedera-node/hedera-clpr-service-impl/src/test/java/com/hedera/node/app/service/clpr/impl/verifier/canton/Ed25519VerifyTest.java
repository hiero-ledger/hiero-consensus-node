// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.hiero.base.crypto.Ed25519;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Ed25519Verify}.
 *
 * <p>The round-trip vector is CONSTRUCTED with the repo's libsodium Ed25519 binding. A real
 * signature over the fixture's Ed25519 mediator message would be preferable, but the signed message
 * for {@code verdict-mediator-sig.hex} is assembled by later verdict-verifier tasks (the gas-fixture
 * provenance does not pin which bytes that signature covers), so this task verifies the primitive
 * itself against a freshly generated key pair and a real Ed25519 signature — not invented bytes.
 */
class Ed25519VerifyTest {

    @Test
    void roundTripsValidSignature() {
        final var keyMaterial = generate();
        final byte[] message = "canton mediator multihash".getBytes();
        final byte[] signature = sign(keyMaterial.priv, message);

        assertThat(Ed25519Verify.verify(keyMaterial.pub, message, signature)).isTrue();
    }

    @Test
    void rejectsTamperedMessage() {
        final var keyMaterial = generate();
        final byte[] message = "canton mediator multihash".getBytes();
        final byte[] signature = sign(keyMaterial.priv, message);
        final byte[] tampered = message.clone();
        tampered[0] ^= 0x01;

        assertThat(Ed25519Verify.verify(keyMaterial.pub, tampered, signature)).isFalse();
    }

    @Test
    void rejectsTamperedSignature() {
        final var keyMaterial = generate();
        final byte[] message = "canton mediator multihash".getBytes();
        final byte[] signature = sign(keyMaterial.priv, message);
        signature[0] ^= 0x01;

        assertThat(Ed25519Verify.verify(keyMaterial.pub, message, signature)).isFalse();
    }

    @Test
    void rejectsWrongKey() {
        final var a = generate();
        final var b = generate();
        final byte[] message = "canton mediator multihash".getBytes();
        final byte[] signature = sign(a.priv, message);

        assertThat(Ed25519Verify.verify(b.pub, message, signature)).isFalse();
    }

    @Test
    void rejectsWrongLengthInputs() {
        assertThat(Ed25519Verify.verify(new byte[31], new byte[] {1}, new byte[64]))
                .isFalse();
        assertThat(Ed25519Verify.verify(new byte[32], new byte[] {1}, new byte[63]))
                .isFalse();
    }

    private record Keys(byte[] pub, byte[] priv) {}

    private static Keys generate() {
        final byte[] seed = new byte[Ed25519.SEED_BYTES];
        new SecureRandom().nextBytes(seed);
        final byte[] pub = new byte[Ed25519.PUBLIC_KEY_BYTES];
        final byte[] secret = new byte[Ed25519.SECRET_KEY_BYTES];
        Ed25519.newKeypair(pub, secret, seed);
        return new Keys(pub, secret);
    }

    private static byte[] sign(final byte[] secretKey, final byte[] message) {
        final byte[] sig = new byte[Ed25519.SIGNATURE_BYTES];
        Ed25519.sign(sig, message, secretKey);
        return sig;
    }
}
