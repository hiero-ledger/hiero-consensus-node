// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static org.hiero.consensus.crypto.SigningImplementation.ED25519_SODIUM;
import static org.hiero.consensus.crypto.SigningImplementation.ED25519_SUN;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.KeyPair;
import java.security.SecureRandom;
import org.hiero.base.crypto.BytesSignatureVerifier;
import org.hiero.base.crypto.BytesSigner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for the SigningFactory class and its related functionality.
 */
class SigningFactoryTest {
    private static final Bytes DATA_VALID = Bytes.fromHex("abcd1234");
    private static final Bytes DATA_INVALID = Bytes.fromHex("abcd");

    /**
     * Since the Sodium implementation of Ed25519 has a JNI interface that is easy to misuse without it being obvious,
     * this test ensures that signatures created both implementations are identical.
     */
    @Test
    void testSodiumCompatibility() {
        // Deterministic SecureRandom for test repeatability
        final SecureRandom secureRandom = new SecureRandom();

        final KeyPair keyPair = SigningFactory.generateKeyPair(SigningSchema.ED25519, secureRandom);
        final BytesSigner jcaSigner = SigningFactory.createSigner(ED25519_SUN, keyPair);
        final BytesSigner sodSigner = SigningFactory.createSigner(ED25519_SODIUM, keyPair);
        final Bytes jcaSignature = jcaSigner.sign(DATA_VALID);
        final Bytes sodSignature = sodSigner.sign(DATA_VALID);

        Assertions.assertEquals(jcaSignature, sodSignature);
    }

    /**
     * Tests that all SigningImplementations can sign and verify data correctly.
     */
    @ParameterizedTest()
    @EnumSource(SigningImplementation.class)
    void testImplementations(final SigningImplementation implementation) {
        final SecureRandom secureRandom = new SecureRandom();
        final KeyPair keyPair = SigningFactory.generateKeyPair(implementation.getSigningSchema(), secureRandom);
        final BytesSigner signer = SigningFactory.createSigner(implementation, keyPair);
        final Bytes signature = signer.sign(DATA_VALID);
        final BytesSignatureVerifier verifier = SigningFactory.createVerifier(implementation, keyPair.getPublic());
        assertTrue(verifier.verify(DATA_VALID, signature), "Verification failed for " + implementation);
        assertFalse(verifier.verify(DATA_INVALID, signature), "Bad data verification passed for " + implementation);
    }
}
