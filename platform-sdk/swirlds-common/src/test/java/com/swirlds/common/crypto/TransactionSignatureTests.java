// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.crypto.config.CryptoConfig;
import com.swirlds.common.test.fixtures.crypto.SignaturePool;
import com.swirlds.common.test.fixtures.crypto.SliceConsumer;
import com.swirlds.common.threading.futures.FuturePool;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TransactionSignatureTests {

    private static CryptoConfig cryptoConfig;
    private static final int PARALLELISM = 16;
    private static ExecutorService executorService;
    private static SignaturePool signaturePool;
    private static Cryptography cryptoProvider;

    @BeforeAll
    public static void startup() throws NoSuchAlgorithmException, NoSuchProviderException {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        cryptoConfig = configuration.getConfigData(CryptoConfig.class);

        assertTrue(cryptoConfig.computeCpuDigestThreadCount() >= 1);

        cryptoProvider = CryptographyHolder.get();
        executorService = Executors.newFixedThreadPool(PARALLELISM);
        signaturePool = new SignaturePool(1024, 4096, true);
    }

    @AfterAll
    public static void shutdown() throws InterruptedException {
        executorService.shutdown();
        executorService.awaitTermination(2, TimeUnit.SECONDS);
    }

    /**
     * Checks correctness of DigitalSignature batch size of exactly one message
     */
    @Test
    public void signatureSizeOfOne()
            throws ExecutionException, InterruptedException, NoSuchProviderException, NoSuchAlgorithmException {
        final TransactionSignature singleSignature = signaturePool.next();

        assertNotNull(singleSignature);
        assertEquals(VerificationStatus.UNKNOWN, singleSignature.getSignatureStatus());

        cryptoProvider.verifySync(singleSignature);

        Future<Void> future = singleSignature.waitForFuture();
        assertNotNull(future);
        future.get();

        assertEquals(VerificationStatus.VALID, singleSignature.getSignatureStatus());
    }

    private void checkSignatures(TransactionSignature... signatures) throws ExecutionException, InterruptedException {
        int numInvalid = 0;

        for (final TransactionSignature sig : signatures) {
            Future<Void> future = sig.waitForFuture();
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

                final boolean isValid = cryptoProvider.verifySync(signature);

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

            Future<Void> future = executorService.submit(new Callable<Void>() {
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
