// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.utility;

import static com.swirlds.base.utility.FileSystemUtils.waitForPathPresence;
import static com.swirlds.base.utility.Retry.DEFAULT_WAIT_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Validates the behavior of the {@link FileSystemUtils} utility class.
 */
class FileSystemUtilsTest {

    private static final Duration SHORT_WAIT_TIME = Duration.of(50, ChronoUnit.MILLIS);
    private static final Duration SHORT_RETRY_DELAY = Duration.of(25, ChronoUnit.MILLIS);

    @TempDir(cleanup = CleanupMode.ALWAYS)
    Path tempDir;

    private static ExecutorService executorService;

    @BeforeAll
    static void setup() {
        executorService = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void teardown() {
        if (!executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }

    @Test
    void delayedFileCreationResolves() {
        final CountDownLatch latch = new CountDownLatch(1);
        final Path targetFile = tempDir.resolve("test.txt");

        final Future<?> filePresence = executorService.submit(() -> {
            await().atMost(DEFAULT_WAIT_TIME.plus(Duration.of(2, ChronoUnit.SECONDS)))
                    .until(() -> waitForPathPresence(targetFile));
        });

        final Future<?> fileCreation = executorService.submit(() -> {
            await().until(() -> {
                latch.await();
                TimeUnit.SECONDS.sleep(1);
                Files.write(targetFile, new byte[] {0x01, 0x02, 0x03});
                return Files.exists(targetFile);
            });
        });

        latch.countDown();
        await().atMost(5, TimeUnit.SECONDS).until(() -> fileCreation.isDone() && filePresence.isDone());
    }

    @Test
    void missingFileTimeout() {
        final Path targetFile = tempDir.resolve("test.txt");
        assertThat(waitForPathPresence(
                        targetFile, Duration.of(50, ChronoUnit.MILLIS), Duration.of(25, ChronoUnit.MILLIS)))
                .isFalse();
    }

    @Test
    void zeroLengthFileTimeout() throws IOException {
        final Path targetFile = tempDir.resolve("test.txt");
        Files.createFile(targetFile);

        assertThat(waitForPathPresence(targetFile, SHORT_WAIT_TIME, SHORT_RETRY_DELAY))
                .isFalse();
    }

    @Test
    void nullPathShouldThrow() {
        assertThatThrownBy(() -> waitForPathPresence(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("path must not be null");
    }

    @Test
    void zeroDelayShouldThrow() {
        assertThatThrownBy(() -> waitForPathPresence(tempDir, SHORT_WAIT_TIME, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The retry delay must be greater than zero (0)");
    }

    @Test
    void negativeDelayShouldThrow() {
        assertThatThrownBy(() -> waitForPathPresence(tempDir, SHORT_WAIT_TIME, SHORT_RETRY_DELAY.negated()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The retry delay must be greater than zero (0)");
    }

    @Test
    void zeroWaitTimeShouldThrow() {
        assertThatThrownBy(() -> waitForPathPresence(tempDir, Duration.ZERO, SHORT_RETRY_DELAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The maximum wait time must be greater than zero (0)");
    }

    @Test
    void negativeWaitTimeShouldThrow() {
        assertThatThrownBy(() -> waitForPathPresence(tempDir, SHORT_WAIT_TIME.negated(), SHORT_RETRY_DELAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The maximum wait time must be greater than zero (0)");
    }

    @Test
    void delayedDirectoryCreationResolves() {
        final CountDownLatch latch = new CountDownLatch(1);
        final Path targetDir = tempDir.resolve("test-dir");

        final Future<?> filePresence = executorService.submit(() -> {
            await().atMost(DEFAULT_WAIT_TIME.plus(Duration.of(2, ChronoUnit.SECONDS)))
                    .until(() -> waitForPathPresence(targetDir));
        });

        final Future<?> fileCreation = executorService.submit(() -> {
            await().until(() -> {
                latch.await();
                TimeUnit.SECONDS.sleep(1);
                Files.createDirectory(targetDir);
                return Files.exists(targetDir);
            });
        });

        latch.countDown();
        await().atMost(5, TimeUnit.SECONDS).until(() -> fileCreation.isDone() && filePresence.isDone());
    }
}
