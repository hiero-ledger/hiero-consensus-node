// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Ed25519Verify}.
 *
 * <p>The round-trip vector is CONSTRUCTED with BouncyCastle's Ed25519 implementation. A real
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
        final var priv = new Ed25519PrivateKeyParameters(new SecureRandom());
        final Ed25519PublicKeyParameters pub = priv.generatePublicKey();
        return new Keys(pub.getEncoded(), priv.getEncoded());
    }

    private static byte[] sign(final byte[] privEncoded, final byte[] message) {
        final var signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(privEncoded, 0));
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }
}
