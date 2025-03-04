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
import com.swirlds.common.test.fixtures.crypto.SliceConsumer;
import com.swirlds.common.threading.futures.FuturePool;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CryptographyTests {
    private static final int PARALLELISM = 16;
    private static final Cryptography CRYPTOGRAPHY = CryptographyFactory.create();
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
            final Hash hash = CRYPTOGRAPHY.digestSync(messages[i].getPayloadDirect(), DigestType.SHA_384);
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
            final SignatureComponents components = extractComponents(signatures[i]);
            assertTrue(CRYPTOGRAPHY.verifySync(
                    components.data(), components.signatureBytes(), components.publicKey(), SignatureType.ED25519));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 49, 98, 101, 25_000, 50_005})
    void verifySyncEcdsaSecp256k1Only(final int count) {
        ecdsaSignaturePool = new EcdsaSignedTxnPool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 64);
        final TransactionSignature[] signatures = new TransactionSignature[count];

        for (int i = 0; i < signatures.length; i++) {
            signatures[i] = ecdsaSignaturePool.next();
            final SignatureComponents components = extractComponents(signatures[i]);
            assertTrue(
                    CRYPTOGRAPHY.verifySync(
                            components.data(),
                            components.signatureBytes(),
                            components.publicKey(),
                            SignatureType.ECDSA_SECP256K1),
                    "Signature should be valid");
        }
    }

    @Test
    void verifySyncInvalidEcdsaSecp256k1() {
        ecdsaSignaturePool = new EcdsaSignedTxnPool(cryptoConfig.computeCpuDigestThreadCount() * PARALLELISM, 64);
        final TransactionSignature signature = ecdsaSignaturePool.next();
        final SignatureComponents components = extractComponents(signature);
        final byte[] data = components.data();
        final byte[] publicKey = components.publicKey();
        final byte[] signatureBytes = components.signatureBytes();
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
        final SignatureComponents components = extractComponents(signature);
        final byte[] data = components.data();
        final byte[] publicKey = components.publicKey();
        final byte[] signatureBytes = components.signatureBytes();
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

    private record SignatureComponents(byte[] data, byte[] publicKey, byte[] signatureBytes) {}

    private SignatureComponents extractComponents(final TransactionSignature signature) {
        final ByteBuffer buffer = ByteBuffer.wrap(signature.getContentsDirect());
        final byte[] data = new byte[signature.getMessageLength()];
        final byte[] publicKey = new byte[signature.getPublicKeyLength()];
        final byte[] signatureBytes = new byte[signature.getSignatureLength()];

        buffer.position(signature.getMessageOffset())
                .get(data)
                .position(signature.getPublicKeyOffset())
                .get(publicKey)
                .position(signature.getSignatureOffset())
                .get(signatureBytes);

        return new SignatureComponents(data, publicKey, signatureBytes);
    }

    private void checkMessages(final Message... messages) throws ExecutionException, InterruptedException {
        int numInvalid = 0;

        for (final Message m : messages) {
            assertNotNull(m.getFuture());
            m.getFuture().get();

            if (!digestPool.isValid(m)) {
                numInvalid++;
            }
        }

        assertEquals(0, numInvalid);
    }

    private void checkSignatures(final TransactionSignature... signatures)
            throws ExecutionException, InterruptedException {
        int numInvalid = 0;

        for (final TransactionSignature sig : signatures) {
            final Future<Void> future = sig.waitForFuture();
            assertNotNull(future);
            future.get();

            if (!VerificationStatus.VALID.equals(sig.getSignatureStatus())) {
                numInvalid++;
            }
        }

        assertEquals(0, numInvalid);
    }

    private void verifyParallel(final TransactionSignature[] signatures, final int parallelism) {
        final FuturePool<Void> futures = parallelExecute(signatures, parallelism, (sItems, offset, count) -> {
            for (int i = offset; i < count; i++) {
                final TransactionSignature signature = sItems[i];

                final boolean isValid = CRYPTOGRAPHY.verifySync(signature);
                signature.setSignatureStatus(((isValid) ? VerificationStatus.VALID : VerificationStatus.INVALID));
            }
        });

        futures.waitForCompletion();
    }

    private FuturePool<Void> parallelExecute(
            final TransactionSignature[] signatures,
            final int parallelism,
            final SliceConsumer<TransactionSignature, Integer, Integer> executor) {
        final FuturePool<Void> futures = new FuturePool<>();

        if (signatures == null || signatures.length == 0) {
            return futures;
        }

        final int sliceSize = (int) Math.ceil((double) signatures.length / (double) parallelism);

        int offset = 0;
        int sliceLength = sliceSize;

        while ((offset + sliceLength) <= signatures.length) {
            //			futures.add(executor, signatures, offset, offset + sliceLength);
            final int fOffset = offset;
            final int fSliceLength = sliceLength;

            final Future<Void> future = executorService.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    executor.accept(signatures, fOffset, fOffset + fSliceLength);
                    return null;
                }
            });

            futures.add(future);

            for (int i = offset; i < (fOffset + fSliceLength); i++) {
                signatures[i].setFuture(future);
            }

            offset += sliceLength;

            if (offset >= signatures.length) {
                break;
            } else if (signatures.length < offset + sliceLength) {
                sliceLength = signatures.length - offset;
            }
        }

        return futures;
    }
}
