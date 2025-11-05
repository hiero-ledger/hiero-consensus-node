package org.hiero.consensus.crypto;

import static org.hiero.consensus.crypto.SigningImplementation.ED25519_SODIUM;
import static org.hiero.consensus.crypto.SigningImplementation.ED25519_SUN;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.base.crypto.BytesVerifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SigningFactoryTest {
    private static final Bytes DATA_VALID = Bytes.fromHex("abcd1234");
    private static final Bytes DATA_INVALID = Bytes.fromHex("abcd");

    /**
     * Since the Sodium implementation of Ed25519 has a JNI interface that is easy to misuse without it being obvious,
     * this test ensures that signatures created both implementations are identical.
     */
    @Test
    void testSodiumCompatibility() throws Exception {
        // Deterministic SecureRandom for test repeatability
        final SecureRandom secureRandom = new SecureRandom();

        final KeyPair keyPair = SigningFactory.generateKeyPair(SigningScheme.ED25519, secureRandom);
        final BytesSigner jcaSigner = SigningFactory.createSigner(ED25519_SUN, keyPair);
        final BytesSigner sodSigner = SigningFactory.createSigner(ED25519_SODIUM, keyPair);
        final Bytes jcaSignature = jcaSigner.sign(DATA_VALID);
        final Bytes sodSignature = sodSigner.sign(DATA_VALID);

        Assertions.assertEquals(jcaSignature, sodSignature);
    }

    @Test
    void testImplementations() throws NoSuchAlgorithmException, NoSuchProviderException {
        final SecureRandom secureRandom = new SecureRandom();
        for (final SigningImplementation implementation : SigningImplementation.values()) {
            final KeyPair keyPair = SigningFactory.generateKeyPair(implementation.getSigningScheme(), secureRandom);
            final BytesSigner signer = SigningFactory.createSigner(implementation, keyPair);
            final Bytes signature = signer.sign(DATA_VALID);
            final BytesVerifier verifier = SigningFactory.createVerifier(implementation, keyPair.getPublic());
            assertTrue(verifier.verify(signature, DATA_VALID), "Verification failed for " + implementation);
            assertFalse(verifier.verify(signature, DATA_INVALID), "Bad data verification passed for " + implementation);
        }
    }
}