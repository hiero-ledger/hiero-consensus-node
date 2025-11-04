package org.hiero.consensus.crypto;

import static org.hiero.consensus.crypto.SigningAlgorithm.ED25519_SODIUM;
import static org.hiero.consensus.crypto.SigningAlgorithm.ED25519_SUN;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SigningFactoryTest {
    private static final Bytes TEST_DATA = Bytes.fromHex("abcd1234");
    private static final Bytes BAD_DATA = Bytes.fromHex("abcd");

    @Test
    void testSodiumCompatibility() throws Exception {
        // Deterministic SecureRandom for test repeatability
        final SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG", "SUN");
        secureRandom.setSeed(123);

        final KeyPair keyPair = SigningFactory.generateKeyPair(ED25519_SUN, secureRandom);
        final BytesSigner jcaSigner = SigningFactory.createSigner(ED25519_SUN, keyPair);
        final BytesSigner sodSigner = SigningFactory.createSigner(ED25519_SODIUM, keyPair);
        final Bytes jcaSignature = jcaSigner.sign(TEST_DATA);
        final Bytes sodSignature = sodSigner.sign(TEST_DATA);

        Assertions.assertEquals(jcaSignature, sodSignature);

        final BytesVerifier jcaVerifier = SigningFactory.createVerifier(ED25519_SUN, keyPair.getPublic());
        final BytesVerifier sodVerifier = SigningFactory.createVerifier(ED25519_SODIUM, keyPair.getPublic());

        assertTrue(jcaVerifier.verify(jcaSignature, TEST_DATA));
        assertTrue(sodVerifier.verify(sodSignature, TEST_DATA));

        assertTrue(jcaVerifier.verify(sodSignature, TEST_DATA));
        assertTrue(sodVerifier.verify(jcaSignature, TEST_DATA));

        Assertions.assertFalse(jcaVerifier.verify(sodSignature, Bytes.fromHex("abcd")));
        Assertions.assertFalse(sodVerifier.verify(jcaSignature, Bytes.fromHex("abcd")));
    }

    @Test
    void test() throws NoSuchAlgorithmException, NoSuchProviderException {
//        for (var provider : Security.getProviders()) {
//            System.out.println(provider.getName());
//        }

//        String keyType = "Ed25519"; // Change to desired key type
//        for (Provider provider : Security.getProviders()) {
//            for (Provider.Service service : provider.getServices()) {
//                if ("KeyPairGenerator".equals(service.getType()) && keyType.equalsIgnoreCase(service.getAlgorithm())) {
//                    System.out.println(provider.getName() + " provides KeyPairGenerator for " + keyType);
//                }
//            }
//        }

        for (final SigningAlgorithm signingType : SigningAlgorithm.values()) {
            System.out.println("Type: " + signingType);
            final KeyPair keyPair = SigningFactory.generateKeyPair(signingType, new SecureRandom());
            final BytesSigner signer = SigningFactory.createSigner(signingType, keyPair);
            signer.sign(Bytes.fromHex("abcd1234"));
        }

    }
}