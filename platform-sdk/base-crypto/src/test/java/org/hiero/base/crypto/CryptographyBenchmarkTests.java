// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import org.hiero.base.crypto.test.fixtures.EcdsaSignedTxnPool;
import org.hiero.base.crypto.test.fixtures.MessageDigestPool;
import org.hiero.base.crypto.test.fixtures.SignaturePool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class CryptographyBenchmarkTests {
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    private record BenchmarkStats(long min, long max, long average, long median) {}

    private static long median(final ArrayList<Long> values) {
        final int middle = values.size() / 2;
        if (values.size() % 2 == 1) {
            return values.get(middle);
        } else {
            return (values.get(middle - 1) + values.get(middle)) / 2;
        }
    }

    private static BenchmarkStats calculateStats(final ArrayList<Long> values) {
        Collections.sort(values);

        long sum = 0;
        for (final long value : values) {
            sum += value;
        }

        return new BenchmarkStats(values.get(0), values.get(values.size() - 1), sum / values.size(), median(values));
    }

    @Test
    @EnabledIfEnvironmentVariable(disabledReason = "Benchmark", named = "benchmark", matches = "true")
    @DisplayName("Verify Ed25519")
    void verifyEd25519() {
        final int count = 50_000;
        final SignaturePool ed25519SignaturePool = new SignaturePool(count, 100, true);
        final TransactionSignature[] signatures = new TransactionSignature[count];

        final ArrayList<Long> verificationTimes = new ArrayList<>();

        for (int i = 0; i < signatures.length; i++) {
            signatures[i] = ed25519SignaturePool.next();

            final long startTime = System.nanoTime();
            CRYPTOGRAPHY.verifySync(signatures[i]);
            final long endTime = System.nanoTime();

            // discard first values, since they take a long time and aren't indicative of actual performance
            if (i > 100) {
                verificationTimes.add(endTime - startTime);
            }
        }

        final BenchmarkStats benchmarkStats = calculateStats(verificationTimes);

        System.out.println("======= Ed25519 Verification =======");
        System.out.println("Max: " + benchmarkStats.max / 1000 + " us");
        System.out.println("Min: " + benchmarkStats.min / 1000 + " us");
        System.out.println("Average: " + benchmarkStats.average / 1000 + " us");
        System.out.println("Median: " + benchmarkStats.median / 1000 + " us");
        System.out.println();

        assertTrue(benchmarkStats.min / 1000 < 175, "Min verification time is too slow");
        assertTrue(benchmarkStats.average / 1000 < 200, "Average verification time is too slow");
        assertTrue(benchmarkStats.median / 1000 < 200, "Median verification time is too slow");
    }

    @Test
    @EnabledIfEnvironmentVariable(disabledReason = "Benchmark", named = "benchmark", matches = "true")
    @DisplayName("Verify EcdsaSecp256k1")
    void verifyEcdsaSecp256k1() {
        final int count = 50_000;
        final EcdsaSignedTxnPool ecdsaSignaturePool = new EcdsaSignedTxnPool(count, 64);
        final TransactionSignature[] signatures = new TransactionSignature[count];

        final ArrayList<Long> verificationTimes = new ArrayList<>();

        for (int i = 0; i < signatures.length; i++) {
            signatures[i] = ecdsaSignaturePool.next();

            final long startTime = System.nanoTime();
            CRYPTOGRAPHY.verifySync(signatures[i]);
            final long endTime = System.nanoTime();

            // discard first values, since they take a long time and aren't indicative of actual performance
            if (i > 100) {
                verificationTimes.add(endTime - startTime);
            }
        }

        final BenchmarkStats benchmarkStats = calculateStats(verificationTimes);

        System.out.println("==== EcdsaSecp256k1 Verification ====");
        System.out.println("Max: " + benchmarkStats.max / 1000 + " us");
        System.out.println("Min: " + benchmarkStats.min / 1000 + " us");
        System.out.println("Average: " + benchmarkStats.average / 1000 + " us");
        System.out.println("Median: " + benchmarkStats.median / 1000 + " us");
        System.out.println();

        assertTrue(benchmarkStats.min / 1000 < 250, "Min verification time is too slow");
        assertTrue(benchmarkStats.average / 1000 < 275, "Average verification time is too slow");
        assertTrue(benchmarkStats.median / 1000 < 275, "Median verification time is too slow");
    }

    @Test
    @EnabledIfEnvironmentVariable(disabledReason = "Benchmark", named = "benchmark", matches = "true")
    @DisplayName("SHA384 Hash")
    void sha384Hash() throws NoSuchAlgorithmException {
        final int count = 5_000_000;
        final MessageDigestPool digestPool = new MessageDigestPool(count, 100);
        final Message[] messages = new Message[count];

        final ArrayList<Long> hashTimes = new ArrayList<>();

        for (int i = 0; i < messages.length; i++) {
            messages[i] = digestPool.next();

            final byte[] payload = messages[i].getPayloadDirect();

            final long startTime = System.nanoTime();
            CRYPTOGRAPHY.digestSync(payload);
            final long endTime = System.nanoTime();

            // discard first values, since they take a long time and aren't indicative of actual performance
            if (i > 100) {
                hashTimes.add(endTime - startTime);
            }
        }

        final BenchmarkStats benchmarkStats = calculateStats(hashTimes);

        System.out.println("======== SHA384 Hashing ========");
        System.out.println("Max: " + benchmarkStats.max + " ns");
        System.out.println("Min: " + benchmarkStats.min + " ns");
        System.out.println("Average: " + benchmarkStats.average + " ns");
        System.out.println("Median: " + benchmarkStats.median + " ns");
        System.out.println();

        assertTrue(benchmarkStats.min < 375, "Min hashing time is too slow");
        assertTrue(benchmarkStats.average < 500, "Average hashing time is too slow");
        assertTrue(benchmarkStats.median < 500, "Median hashing time is too slow");
    }
}
