// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.config.CryptoConfig;
import org.hiero.base.crypto.test.fixtures.SignaturePool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TransactionSignatureTests {

    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();
    private static CryptoConfig cryptoConfig;
    private static final int PARALLELISM = 16;
    private static ExecutorService executorService;
    private static SignaturePool signaturePool;

    @BeforeAll
    public static void startup() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        cryptoConfig = configuration.getConfigData(CryptoConfig.class);

        assertTrue(cryptoConfig.computeCpuDigestThreadCount() >= 1);

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
    public void signatureSizeOfOne() {
        final TransactionSignature singleSignature = signaturePool.next();

        assertNotNull(singleSignature);
        assertEquals(VerificationStatus.UNKNOWN, singleSignature.getSignatureStatus());

        CRYPTOGRAPHY.verifySync(singleSignature);

        assertEquals(VerificationStatus.VALID, singleSignature.getSignatureStatus());
    }
}
