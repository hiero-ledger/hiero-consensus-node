// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

import static com.swirlds.common.stream.HashCalculatorTest.PAY_LOAD_SIZE_4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.test.fixtures.stream.ObjectForTestStream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Test;

class RunningHashCalculatorTest {
    private static Cryptography cryptography = CryptographyProvider.getInstance();

    @Test
    void runningHashTest() throws InterruptedException {
        final Hash initialHash = new Hash(new byte[DigestType.SHA_384.digestLength()]);
        final RunningHashCalculatorForStream<ObjectForTestStream> runningHashCalculator =
                new RunningHashCalculatorForStream();
        runningHashCalculator.setRunningHash(initialHash);

        Hash expected = initialHash;
        for (int i = 0; i < 100; i++) {
            ObjectForTestStream object = ObjectForTestStream.getRandomObjectForTestStream(PAY_LOAD_SIZE_4);
            runningHashCalculator.addObject(object);
            expected = cryptography.calcRunningHash(expected, object.getHash());
            assertEquals(
                    expected,
                    runningHashCalculator.getRunningHash(),
                    "Actual runningHash doesn't match expected value");
        }
    }

    @Test
    void nullInitialHashTest() throws InterruptedException {
        final RunningHashCalculatorForStream<ObjectForTestStream> runningHashCalculator =
                new RunningHashCalculatorForStream();
        runningHashCalculator.setRunningHash(null);

        Hash expected = null;
        for (int i = 0; i < 100; i++) {
            ObjectForTestStream object = ObjectForTestStream.getRandomObjectForTestStream(PAY_LOAD_SIZE_4);
            runningHashCalculator.addObject(object);
            expected = cryptography.calcRunningHash(expected, object.getHash());
            assertEquals(
                    expected,
                    runningHashCalculator.getRunningHash(),
                    "Actual runningHash doesn't match expected value");
        }
    }

    @Test
    void newHashIsNullTest() {
        Exception exception = assertThrows(
                IllegalArgumentException.class,
                () -> cryptography.calcRunningHash(null, null),
                "should throw IllegalArgumentException when newHashToAdd is null");
        assertTrue(
                exception.getMessage().contains("newHashToAdd is null"),
                "the exception should contain expected message");
    }
}
