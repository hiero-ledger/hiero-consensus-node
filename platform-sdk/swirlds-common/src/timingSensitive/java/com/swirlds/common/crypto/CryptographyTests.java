// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto;

import static org.hiero.consensus.model.utility.CommonUtils.unhex;
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
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.SerializableHashable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CryptographyTests {
    private static final int PARALLELISM = 16;
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();
    private static final Hash KNOWN_DUMMY_SERIALIZABLE_HASH = new Hash(
            unhex("a19330d1f361a9e8f6433cce909b5d04ec0216788acef9e8977633a8332a1b08ab6b65d821e8ff30f64f1353d46182d1"));
    private static CryptoConfig cryptoConfig;
    private static MessageDigestPool digestPool;
    private static SignaturePool ed25519SignaturePool;
    private static ExecutorService executorService;
    private static EcdsaSignedTxnPool ecdsaSignaturePool;

    @BeforeAll
    public static void startup() throws NoSuchAlgorithmException {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        cryptoConfig = configuration.getConfigData(CryptoConfig.class);

        assertTrue(cryptoConfig.computeCpuDigestThreadCount() >= 1);

        executorService = Executors.newFixedThreadPool(PARALLELISM);

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
            final Hash hash = CRYPTOGRAPHY.digestSync(messages[i].getPayloadDirect());
            assertTrue(digestPool.isValid(messages[i], hash.copyToByteArray()));
        }
    }

    @Test
    void hashableSerializableTest() {
        final SerializableHashable hashable = new SerializableHashableDummy(123, "some string");
        assertNull(hashable.getHash());
        CRYPTOGRAPHY.digestSync(hashable);
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
            assertTrue(CRYPTOGRAPHY.verifySync(signatures[i]));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 49, 98, 101, 25_000, 50_005})
    void verifySyncEcdsaSecp256k1Only(final int count) {
        ecdsaSignaturePool = new EcdsaSignedTxnPool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 64);
        final TransactionSignature[] signatures = new TransactionSignature[count];

        for (int i = 0; i < signatures.length; i++) {
            signatures[i] = ecdsaSignaturePool.next();
            assertTrue(CRYPTOGRAPHY.verifySync(signatures[i]), "Signature should be valid");
        }
    }

    @Test
    void verifySyncInvalidEcdsaSecp256k1() {
        ecdsaSignaturePool = new EcdsaSignedTxnPool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 64);
        final TransactionSignature signature = ecdsaSignaturePool.next();
        final byte[] data = signature.getMessage().toByteArray();
        final byte[] publicKey = signature.getPublicKey().toByteArray();
        final byte[] signatureBytes = signature.getSignature().toByteArray();
        Configurator.setAllLevels("", Level.ALL);
        assertFalse(
                CRYPTOGRAPHY.verifySync(
                        data,
                        Arrays.copyOfRange(signatureBytes, 1, signatureBytes.length),
                        publicKey,
                        SignatureType.ECDSA_SECP256K1),
                "Fails for invalid signature");

        assertFalse(
                CRYPTOGRAPHY.verifySync(
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
        final byte[] data = signature.getMessage().toByteArray();
        final byte[] publicKey = signature.getPublicKey().toByteArray();
        final byte[] signatureBytes = signature.getSignature().toByteArray();
        Configurator.setAllLevels("", Level.ALL);

        assertFalse(
                CRYPTOGRAPHY.verifySync(
                        data,
                        Arrays.copyOfRange(signatureBytes, 1, signatureBytes.length),
                        publicKey,
                        SignatureType.ED25519),
                "Fails for invalid signature");

        assertFalse(
                CRYPTOGRAPHY.verifySync(
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
        assertTrue(CRYPTOGRAPHY.verifySync(signature), "Should be a valid signature");
    }

    @Test
    void verifySyncEcdsaSignature() {
        ecdsaSignaturePool = new EcdsaSignedTxnPool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 64);
        final TransactionSignature signature = ecdsaSignaturePool.next();
        assertTrue(CRYPTOGRAPHY.verifySync(signature), "Should be a valid signature");
    }
}
