// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto;

import static com.swirlds.common.utility.CommonUtils.unhex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.crypto.config.CryptoConfig;
import com.swirlds.common.test.fixtures.crypto.EcdsaSignedTxnPool;
import com.swirlds.common.test.fixtures.crypto.MessageDigestPool;
import com.swirlds.common.test.fixtures.crypto.SerializableHashableDummy;
import com.swirlds.common.test.fixtures.crypto.SignaturePool;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CryptographyTests {
    private static CryptoConfig cryptoConfig;
    private static final int PARALLELISM = 16;
    private static final Hash KNOWN_DUMMY_SERIALIZABLE_HASH = new Hash(
            unhex("a19330d1f361a9e8f6433cce909b5d04ec0216788acef9e8977633a8332a1b08ab6b65d821e8ff30f64f1353d46182d1"));
    private static MessageDigestPool digestPool;
    private static SignaturePool ed25519SignaturePool;
    private static ExecutorService executorService;
    private static EcdsaSignedTxnPool ecdsaSignaturePool;
    private static Cryptography cryptography;

    @BeforeAll
    public static void startup() throws NoSuchAlgorithmException {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        cryptoConfig = configuration.getConfigData(CryptoConfig.class);

        assertTrue(cryptoConfig.computeCpuDigestThreadCount() >= 1);

        executorService = Executors.newFixedThreadPool(PARALLELISM);
        cryptography = CryptographyHolder.get();

        digestPool = new MessageDigestPool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 100);
    }

    @AfterAll
    public static void shutdown() throws InterruptedException {
        executorService.shutdown();
        executorService.awaitTermination(2, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 49, 98, 101, 25_000, 50_005})
    void digestSyncRawTest(final int count) {
        final Message[] messages = new Message[count];

        for (int i = 0; i < messages.length; i++) {
            messages[i] = digestPool.next();
            final Hash hash = cryptography.digestSync(messages[i].getPayloadDirect(), DigestType.SHA_384);
            assertTrue(digestPool.isValid(messages[i], hash.copyToByteArray()));
        }
    }

    @Test
    void hashableSerializableTest() {
        final SerializableHashable hashable = new SerializableHashableDummy(123, "some string");
        assertNull(hashable.getHash());
        cryptography.digestSync(hashable);
        assertNotNull(hashable.getHash());

        final Hash hash = hashable.getHash();
        assertEquals(KNOWN_DUMMY_SERIALIZABLE_HASH, hash);
        assertEquals(KNOWN_DUMMY_SERIALIZABLE_HASH.getBytes(), hash.getBytes());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 49, 98, 101, 25_000, 50_005})
    void verifySyncEd25519Only(final int count) {
        ed25519SignaturePool = new SignaturePool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 100, true);
        final TransactionSignature[] signatures = new TransactionSignature[count];

        for (int i = 0; i < signatures.length; i++) {
            signatures[i] = ed25519SignaturePool.next();
            assertTrue(cryptography.verifySync(
                    signatures[i].getMessage(),
                    signatures[i].getSignature(),
                    signatures[i].getPublicKey(),
                    SignatureType.ED25519));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 49, 98, 101, 25_000, 50_005})
    void verifySyncEcdsaSecp256k1Only(final int count) {
        ecdsaSignaturePool = new EcdsaSignedTxnPool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 64);
        final TransactionSignature[] signatures = new TransactionSignature[count];

        for (int i = 0; i < signatures.length; i++) {
            signatures[i] = ecdsaSignaturePool.next();
            assertTrue(
                    cryptography.verifySync(
                            signatures[i].getMessage(),
                            signatures[i].getSignature(),
                            signatures[i].getPublicKey(),
                            SignatureType.ECDSA_SECP256K1),
                    "Signature should be valid");
        }
    }

    @Test
    void verifySyncInvalidEcdsaSecp256k1() {
        ecdsaSignaturePool = new EcdsaSignedTxnPool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 64);
        final TransactionSignature signature = ecdsaSignaturePool.next();
        final byte[] data = signature.getMessage();
        final byte[] publicKey = signature.getPublicKey();
        final byte[] signatureBytes = signature.getSignature();
        Configurator.setAllLevels("", Level.ALL);
        assertFalse(
                cryptography.verifySync(
                        data,
                        Arrays.copyOfRange(signatureBytes, 1, signatureBytes.length),
                        publicKey,
                        SignatureType.ECDSA_SECP256K1),
                "Fails for invalid signature");

        assertFalse(
                cryptography.verifySync(
                        data,
                        signatureBytes,
                        Arrays.copyOfRange(publicKey, 1, publicKey.length),
                        SignatureType.ECDSA_SECP256K1),
                "Fails for invalid public key");
    }

    @Test
    void verifySyncInvalidEd25519() {
        ed25519SignaturePool = new SignaturePool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 100, true);
        final TransactionSignature signature = ed25519SignaturePool.next();
        final byte[] data = signature.getMessage();
        final byte[] publicKey = signature.getPublicKey();
        final byte[] signatureBytes = signature.getSignature();
        Configurator.setAllLevels("", Level.ALL);

        assertFalse(
                cryptography.verifySync(
                        data,
                        Arrays.copyOfRange(signatureBytes, 1, signatureBytes.length),
                        publicKey,
                        SignatureType.ED25519),
                "Fails for invalid signature");

        assertFalse(
                cryptography.verifySync(
                        data,
                        signatureBytes,
                        Arrays.copyOfRange(publicKey, 1, publicKey.length),
                        SignatureType.ED25519),
                "Fails for invalid public key");
    }

    @Test
    void verifySyncEd25519Signature() {
        ed25519SignaturePool = new SignaturePool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 100, true);
        final TransactionSignature signature = ed25519SignaturePool.next();
        assertTrue(cryptography.verifySync(signature), "Should be a valid signature");
    }

    @Test
    void verifySyncEcdsaSignature() {
        ecdsaSignaturePool = new EcdsaSignedTxnPool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 64);
        final TransactionSignature signature = ecdsaSignaturePool.next();
        assertTrue(cryptography.verifySync(signature), "Should be a valid signature");
    }
}
