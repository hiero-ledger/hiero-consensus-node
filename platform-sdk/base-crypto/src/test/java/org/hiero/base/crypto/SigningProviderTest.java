// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.security.NoSuchAlgorithmException;
import java.util.SplittableRandom;
import org.hiero.base.crypto.config.CryptoConfig;
import org.hiero.base.crypto.test.fixtures.ECDSASigningProvider;
import org.hiero.base.crypto.test.fixtures.ED25519SigningProvider;
import org.hiero.base.crypto.test.fixtures.EcdsaUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class SigningProviderTest {
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();
    private static CryptoConfig cryptoConfig;
    private static int TEST_TIMES = 100;

    @BeforeAll
    public static void startup() throws NoSuchAlgorithmException {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        cryptoConfig = configuration.getConfigData(CryptoConfig.class);

        assertTrue(cryptoConfig.computeCpuDigestThreadCount() > 1, "Check cpu digest thread count");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 32, 500, 1000})
    @Disabled("This test needs to be investigated")
    void ECDSASigningProviderTest(int transactionSize) throws Exception {
        SplittableRandom random = new SplittableRandom();
        final ECDSASigningProvider ecdsaSigningProvider = new ECDSASigningProvider();
        assertTrue(ecdsaSigningProvider.isAlgorithmAvailable(), "Check ECDSA is supported");
        assertEquals(EcdsaUtils.SIGNATURE_LENGTH, ecdsaSigningProvider.getSignatureLength(), "Check signature length");
        assertEquals(88, ecdsaSigningProvider.getPrivateKeyBytes().length, "Check key length");

        for (int i = 0; i < TEST_TIMES; i++) {

            final byte[] msg = new byte[transactionSize];
            random.nextBytes(msg);
            final byte[] signature = ecdsaSigningProvider.sign(msg);
            assertTrue(
                    CRYPTOGRAPHY.verifySync(
                            msg, signature, ecdsaSigningProvider.getPublicKeyBytes(), SignatureType.ECDSA_SECP256K1),
                    "check ECDSA result");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 32, 500, 1000})
    void ED25519SigningProviderTest(int transactionSize) throws Exception {
        SplittableRandom random = new SplittableRandom();
        final ED25519SigningProvider ed25519SigningProvider = new ED25519SigningProvider();
        assertTrue(ed25519SigningProvider.isAlgorithmAvailable(), "Check ED25519 is supported");
        assertEquals(
                ED25519SigningProvider.SIGNATURE_LENGTH,
                ed25519SigningProvider.getSignatureLength(),
                "Check signature length");
        assertEquals(
                ED25519SigningProvider.PRIVATE_KEY_LENGTH,
                ed25519SigningProvider.getPrivateKeyBytes().length,
                "Check key length");
        for (int i = 0; i < TEST_TIMES; i++) {

            final byte[] msg = new byte[transactionSize];
            random.nextBytes(msg);
            final byte[] signature = ed25519SigningProvider.sign(msg);
            assertTrue(
                    CRYPTOGRAPHY.verifySync(
                            msg, signature, ed25519SigningProvider.getPublicKeyBytes(), SignatureType.ED25519),
                    "check ED25519 result");
        }
    }
}
