// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyDoesNotThrow;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphabetic;
import static org.apache.commons.lang3.RandomUtils.nextBoolean;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCompactor;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.verification.VerificationMode;

class MerkleDbCompactionCoordinatorTest {

    @Mock
    private MerkleDbStatisticsUpdater statisticsUpdater;

    @Mock
    private DataFileCompactor objectKeyToPath;

    @Mock
    private DataFileCompactor hashStoreDisk;

    @Mock
    private DataFileCompactor pathToKeyValue;

    private MerkleDbCompactionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        coordinator = new MerkleDbCompactionCoordinator("test", CONFIGURATION.getConfigData(MerkleDbConfig.class));
        coordinator.enableBackgroundCompaction();
    }

    @AfterEach
    void cleanUp() {
        coordinator.stopAndDisableBackgroundCompaction();
        assertEventuallyTrue(
                () -> ((ThreadPoolExecutor) MerkleDbCompactionCoordinator.getCompactionExecutor(
                                CONFIGURATION.getConfigData(MerkleDbConfig.class)))
                        .getQueue()
                        .isEmpty(),
                Duration.ofSeconds(1),
                "Queue is not empty");
        assertEventuallyEquals(
                0,
                () -> ((ThreadPoolExecutor) MerkleDbCompactionCoordinator.getCompactionExecutor(
                                CONFIGURATION.getConfigData(MerkleDbConfig.class)))
                        .getActiveCount(),
                Duration.ofSeconds(1),
                "Active task count is not 0");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testCompactDiskStoreForKeyToPathAsync(boolean compactionPassed) throws IOException, InterruptedException {
        testCompaction(
                objectKeyToPath,
                () -> coordinator.compactIfNotRunningYet("keyToPath", objectKeyToPath),
                // expect compaction to be started
                true,
                compactionPassed);
    }

    @Test
    void testCompactDiskStoreForKeyToPathAsync_failed() throws IOException, InterruptedException {
        testCompactionFailed(objectKeyToPath, () -> coordinator.compactIfNotRunningYet("keyToPath", objectKeyToPath));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testCompactDiskStoreForHashesAsync(boolean compactionPassed) throws IOException, InterruptedException {
        testCompaction(
                hashStoreDisk,
                () -> coordinator.compactIfNotRunningYet("hashStoreDisk", hashStoreDisk),
                // expect compaction to be started
                true,
                compactionPassed);
    }

    @Test
    void testCompactDiskStoreForHashesAsync_failed() throws IOException, InterruptedException {
        testCompactionFailed(hashStoreDisk, () -> coordinator.compactIfNotRunningYet("hashStoreDisk", hashStoreDisk));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testCompactPathToKeyValueAsync(boolean compactionPassed) throws IOException, InterruptedException {
        testCompaction(
                pathToKeyValue,
                () -> coordinator.compactIfNotRunningYet("pathToKeyValue", pathToKeyValue),
                // expect compaction to be started
                true,
                compactionPassed);
    }

    @Test
    void testCompactPathToKeyValueAsync_failed() throws IOException, InterruptedException {
        testCompactionFailed(
                pathToKeyValue, () -> coordinator.compactIfNotRunningYet("pathToKeyValue", pathToKeyValue));
    }

    @Test
    void testCompactDiskStoreForKeyToPathAsync_compactionDisabled() throws IOException, InterruptedException {
        stopAndDisableCompaction();
        testCompaction(
                objectKeyToPath,
                () -> coordinator.compactIfNotRunningYet("keyToPath", objectKeyToPath),
                // compaction shouldn't be started
                false,
                nextBoolean());
    }

    @Test
    void testCompactDiskStoreForHashesAsync_compactionDisabled() throws IOException, InterruptedException {
        stopAndDisableCompaction();
        testCompaction(
                hashStoreDisk,
                () -> coordinator.compactIfNotRunningYet("hashStoreDisk", hashStoreDisk),
                // compaction shouldn't be started
                false,
                nextBoolean());
    }

    @Test
    void testCompactPathToKeyValueAsync_compactionDisabled() throws IOException, InterruptedException {
        stopAndDisableCompaction();
        testCompaction(
                pathToKeyValue,
                () -> coordinator.compactIfNotRunningYet("pathToKeyValue", pathToKeyValue),
                // compaction shouldn't be started
                false,
                nextBoolean());
    }

    @Test
    void testCompactionCancelled() throws IOException, InterruptedException {
        CountDownLatch compactLatch = new CountDownLatch(1);
        CountDownLatch testLatch = new CountDownLatch(3);
        initCompactorMock(objectKeyToPath, nextBoolean(), testLatch, compactLatch, new AtomicBoolean());
        initCompactorMock(pathToKeyValue, nextBoolean(), testLatch, compactLatch, new AtomicBoolean());
        initCompactorMock(hashStoreDisk, nextBoolean(), testLatch, compactLatch, new AtomicBoolean());

        coordinator.compactIfNotRunningYet("keyToPath", objectKeyToPath);
        coordinator.compactIfNotRunningYet("hashStoreDisk", hashStoreDisk);
        coordinator.compactIfNotRunningYet("pathToKeyValue", pathToKeyValue);

        // let all compactions get to the latch
        assertTrue(await(testLatch), "Test latch wasn't released");

        // latch is released by interruption of the compaction thread
        coordinator.stopAndDisableBackgroundCompaction();

        assertEventuallyDoesNotThrow(
                () -> {
                    try {
                        verify(objectKeyToPath).compact();
                        verify(pathToKeyValue).compact();
                        verify(hashStoreDisk).compact();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    verifyNoInteractions(statisticsUpdater);
                },
                Duration.ofMillis(100),
                "Unexpected mock state");
    }

    @Test
    void testCompactionWithNullNullables() throws IOException, InterruptedException {
        String table = randomAlphabetic(7);
        coordinator = new MerkleDbCompactionCoordinator(table, CONFIGURATION.getConfigData(MerkleDbConfig.class));
        coordinator.enableBackgroundCompaction();

        testCompaction(
                pathToKeyValue,
                () -> coordinator.compactIfNotRunningYet("pathToKeyValue", pathToKeyValue),
                // expect compaction to be started
                true,
                true);

        reset(pathToKeyValue);

        CountDownLatch compactLatch = new CountDownLatch(1);
        CountDownLatch testLatch = new CountDownLatch(1);
        AtomicBoolean compactLatchAwaitResult = new AtomicBoolean(false);
        initCompactorMock(pathToKeyValue, nextBoolean(), testLatch, compactLatch, compactLatchAwaitResult);

        coordinator.compactIfNotRunningYet("pathToKeyValue", pathToKeyValue);

        // let all compactions get to the latch
        assertTrue(await(testLatch), "Test latch wasn't released");
        compactLatch.countDown();
        assertEventuallyTrue(compactLatchAwaitResult::get, Duration.ofSeconds(1), "Compaction latch wasn't released");

        // latch is released by interruption of the compaction thread
        coordinator.stopAndDisableBackgroundCompaction();

        assertEventuallyDoesNotThrow(
                () -> {
                    try {
                        verify(pathToKeyValue).compact();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    verifyNoInteractions(statisticsUpdater);
                },
                Duration.ofMillis(100),
                "Unexpected mock state");
    }

    private void stopAndDisableCompaction() {
        assertTrue(coordinator.isCompactionEnabled(), "Compaction is supposed to be enabled");
        coordinator.stopAndDisableBackgroundCompaction();
        assertFalse(coordinator.isCompactionEnabled(), "Compaction is supposed to be disabled");
    }

    private void testCompaction(
            DataFileCompactor compactorToTest,
            Runnable methodCall,
            boolean expectCompactionStarted,
            boolean compactionPassed)
            throws IOException, InterruptedException {
        CountDownLatch testLatch = new CountDownLatch(1);
        CountDownLatch compactLatch = new CountDownLatch(1);
        AtomicBoolean compactLatchAwaitResult = new AtomicBoolean(false);
        initCompactorMock(compactorToTest, compactionPassed, testLatch, compactLatch, compactLatchAwaitResult);

        // run twice to make sure that the second call is discarded because one compaction is already in progress
        methodCall.run();
        methodCall.run();
        if (expectCompactionStarted) {
            assertTrue(await(testLatch), "Test latch wasn't released");
        }
        compactLatch.countDown();
        if (expectCompactionStarted) {
            assertEventuallyTrue(
                    compactLatchAwaitResult::get, Duration.ofSeconds(1), "Compaction latch wasn't released");
        }

        assertCompactable(compactorToTest, expectCompactionStarted);

        reset(objectKeyToPath, pathToKeyValue, hashStoreDisk, statisticsUpdater);
        initCompactorMock(compactorToTest, compactionPassed, testLatch, compactLatch, new AtomicBoolean());

        // the second time it should succeed as well
        methodCall.run();
        assertCompactable(compactorToTest, expectCompactionStarted);
    }

    private void testCompactionFailed(DataFileCompactor compactorToTest, Runnable methodCall)
            throws IOException, InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        when(compactorToTest.compact()).thenAnswer(invocation -> {
            await(latch);
            throw new RuntimeException("testCompactionFailed");
        });

        methodCall.run();
        latch.countDown();

        assertEventuallyDoesNotThrow(
                () -> {
                    try {
                        verify(compactorToTest).compact();
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    verifyNoInteractions(statisticsUpdater);
                },
                Duration.ofSeconds(1),
                "Unexpected mock state");
    }

    private boolean await(CountDownLatch latch) throws InterruptedException {
        return latch.await(500, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private void initCompactorMock(
            DataFileCompactor compactorToTest,
            boolean compactionPassed,
            CountDownLatch testLatch,
            CountDownLatch compactLatch,
            AtomicBoolean awaitResult)
            throws IOException, InterruptedException {
        when(compactorToTest.compact()).thenAnswer(invocation -> {
            testLatch.countDown();
            awaitResult.set(await(compactLatch));
            return compactionPassed;
        });
    }

    private void assertCompactable(DataFileCompactor compactorToTest, boolean expectCompactionStarted) {
        assertEventuallyDoesNotThrow(
                () -> {
                    VerificationMode compactionVerificationMode = expectCompactionStarted ? times(1) : never();
                    try {
                        verify(compactorToTest, compactionVerificationMode).compact();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                Duration.ofSeconds(1),
                "Unexpected mock state");
    }
}
