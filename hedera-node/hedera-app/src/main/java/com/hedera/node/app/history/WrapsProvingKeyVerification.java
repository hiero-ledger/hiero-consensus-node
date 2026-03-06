// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.history.impl.ReadableHistoryStoreImpl;
import com.hedera.node.app.history.impl.WritableHistoryStoreImpl;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Encapsulates the WRAPS proving key hash verification lifecycle. Created early
 * by {@link com.hedera.node.app.Hedera} and shared with
 * {@link com.hedera.node.app.workflows.handle.record.SystemTransactions} via Dagger
 * (following the same pattern as {@code WrappedRecordBlockHashMigration}).
 *
 * <p>During {@code onStateInitialized()}, the on-disk proving key file is verified
 * against the bootstrap hash from config. If the hash matches and state does not yet
 * contain it, the hash is held as pending. Later, during {@code doPostUpgradeSetup()},
 * the pending hash is persisted to state.
 */
public class WrapsProvingKeyVerification {
    private static final Logger log = LogManager.getLogger(WrapsProvingKeyVerification.class);

    private static final Duration DEFAULT_DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);

    private final Executor downloadExecutor;
    private final Duration downloadTimeout;

    @NonNull
    private volatile Bytes pendingHash = Bytes.EMPTY;

    @NonNull
    private volatile CompletableFuture<Void> downloadFuture = CompletableFuture.completedFuture(null);

    public WrapsProvingKeyVerification() {
        this(ForkJoinPool.commonPool(), DEFAULT_DOWNLOAD_TIMEOUT);
    }

    public WrapsProvingKeyVerification(@NonNull final Executor downloadExecutor) {
        this(downloadExecutor, DEFAULT_DOWNLOAD_TIMEOUT);
    }

    public WrapsProvingKeyVerification(
            @NonNull final Executor downloadExecutor, @NonNull final Duration downloadTimeout) {
        this.downloadExecutor = requireNonNull(downloadExecutor);
        this.downloadTimeout = requireNonNull(downloadTimeout);
    }

    /**
     * Returns the pending expected WRAPS proving key hash, or {@link Bytes#EMPTY} if none.
     */
    @NonNull
    public Bytes pendingHash() {
        return pendingHash;
    }

    /**
     * Verifies the WRAPS proving key file and, if appropriate, stores the hash
     * as pending for later persistence.
     *
     * @param state the current state
     * @param bootstrapConfig the bootstrap configuration
     * @param downloader the downloader to invoke if the file is missing or corrupt
     */
    public void verify(
            @NonNull final State state,
            @NonNull final Configuration bootstrapConfig,
            @NonNull final WrapsProvingKeyDownloader downloader) {
        requireNonNull(state);
        requireNonNull(bootstrapConfig);
        requireNonNull(downloader);
        final var tssConfig = bootstrapConfig.getConfigData(TssConfig.class);
        if (!tssConfig.wrapsEnabled()) {
            log.info("WRAPS not enabled, skipping proving key hash verification");
            return;
        } else {
            log.fatal("matt: WRAPS enabled and verifying");
        }

        final var bootstrapHash = tssConfig.wrapsProvingKeyHash();
        if (bootstrapHash.isBlank()) {
            throw new IllegalArgumentException("WRAPS proving key hash is required");
        }

        final var provingKeyPath = Paths.get(tssConfig.wrapsProvingKeyPath());
        final var downloadUrl = tssConfig.wrapsProvingKeyDownloadUrl();
        final var historyStates = state.getReadableStates(HistoryService.NAME);
        final var store = new ReadableHistoryStoreImpl(historyStates);
        final var existingHash = store.getWrapsProvingKeyHash();
        verifyAndRememberPendingHash(provingKeyPath, bootstrapHash, existingHash, downloadUrl, downloader);
    }

    /**
     * Decides if a valid pending hash is present and, if conditions are appropriate, persists it
     * to state. This method should be called exactly once at startup, specifically during
     * {@code doPostUpgradeSetup()}.
     *
     * @param state the current state
     * @param config the current configuration
     */
    public void maybePersistPendingHash(@NonNull final State state, @NonNull final Configuration config) {
        requireNonNull(state);
        requireNonNull(config);
        awaitDownloadIfPending();
        log.fatal("matt: maybePersistPendingHash called with pendingHash={}", pendingHash);
        if (pendingHash.equals(Bytes.EMPTY)) {
            return;
        }
        final var historyStates = state.getWritableStates(HistoryService.NAME);
        final var historyStore = new WritableHistoryStoreImpl(historyStates);
        final var currProvingKeyHash = historyStore.getWrapsProvingKeyHash();
        if (currProvingKeyHash == null) {
            historyStore.setWrapsProvingKeyHash(pendingHash);
            ((CommittableWritableStates) historyStates).commit();
            log.info("Persisted first WRAPS proving key hash {} to state", pendingHash);
        } else if (currProvingKeyHash.equals(pendingHash)) {
            log.info("Pending WRAPS proving key hash {} matches proving key in state", pendingHash);
        } else {
            final var tssConfig = config.getConfigData(TssConfig.class);
            final var configHash = tssConfig.wrapsProvingKeyHash();
            if (!configHash.isBlank() && Bytes.fromHex(configHash).equals(pendingHash)) {
                log.info(
                        "Overwriting previous WRAPS proving key hash {} with new pending hash {}",
                        currProvingKeyHash,
                        pendingHash);
                historyStore.setWrapsProvingKeyHash(pendingHash);
                ((CommittableWritableStates) historyStates).commit();
            } else {
                throw new IllegalStateException("WRAPS proving key hash mismatch: state=" + currProvingKeyHash
                        + " pending=" + pendingHash + " config=" + configHash);
            }
        }
    }

    private void verifyAndRememberPendingHash(
            @NonNull final Path provingKeyPath,
            @NonNull final String bootstrapHash,
            @Nullable final Bytes existingHashInState,
            @NonNull final String downloadUrl,
            @NonNull final WrapsProvingKeyDownloader downloader) {
        final var expectedHash = Bytes.fromHex(bootstrapHash);
        log.fatal(
                "matt: verifyAndRememberPendingHash path={}, expectedHash={}, existingInState={}, downloadUrl={}",
                provingKeyPath,
                expectedHash,
                existingHashInState,
                downloadUrl);
        if (!Files.exists(provingKeyPath)) {
            log.fatal("matt: proving key file NOT found at {}, will download", provingKeyPath);
            log.info("WRAPS proving key file not found at {}. Initiating download", provingKeyPath);
            asyncDownloadAndVerify(provingKeyPath, expectedHash, downloadUrl, downloader);
            return;
        }
        final Bytes fileHash = hashFile(provingKeyPath);
        log.fatal(
                "matt: proving key file found at {}, fileHash={}, matches={}",
                provingKeyPath,
                fileHash,
                fileHash.equals(expectedHash));
        if (!fileHash.equals(expectedHash)) {
            log.warn(
                    "WRAPS proving key hash mismatch at {} (expected={}, actual={}), initiating download",
                    provingKeyPath,
                    expectedHash,
                    fileHash);
            asyncDownloadAndVerify(provingKeyPath, expectedHash, downloadUrl, downloader);
            return;
        }
        this.pendingHash = expectedHash;
        log.fatal("matt: pendingHash set synchronously to {}", expectedHash);
    }

    private void asyncDownloadAndVerify(
            @NonNull final Path provingKeyPath,
            @NonNull final Bytes expectedHash,
            @NonNull final String downloadUrl,
            @NonNull final WrapsProvingKeyDownloader downloader) {
        log.fatal("matt: asyncDownloadAndVerify starting, url={}, targetPath={}", downloadUrl, provingKeyPath);
        downloadFuture = CompletableFuture.runAsync(
                () -> {
                    log.fatal(
                            "matt: async download runnable executing on thread {}",
                            Thread.currentThread().getName());
                    try {
                        downloader.download(downloadUrl, provingKeyPath);
                        log.fatal("matt: download completed successfully to {}", provingKeyPath);
                    } catch (final IOException e) {
                        log.fatal("matt: download FAILED with IOException: {}", e.getMessage());
                        log.error("Failed to download WRAPS proving key from {}", downloadUrl, e);
                        return;
                    }
                    final Bytes downloadedHash = hashFile(provingKeyPath);
                    log.fatal(
                            "matt: downloaded file hash={}, expected={}, matches={}",
                            downloadedHash,
                            expectedHash,
                            downloadedHash.equals(expectedHash));
                    if (!downloadedHash.equals(expectedHash)) {
                        throw new IllegalStateException("Downloaded WRAPS proving key hash mismatch: expected="
                                + expectedHash + ", actual=" + downloadedHash);
                    }
                    this.pendingHash = expectedHash;
                    log.fatal("matt: pendingHash set to {} after successful download", expectedHash);
                    log.info("Successfully downloaded and verified WRAPS proving key (hash={})", expectedHash);
                },
                downloadExecutor);
        log.fatal("matt: asyncDownloadAndVerify future created, isDone={}", downloadFuture.isDone());
    }

    private void awaitDownloadIfPending() {
        log.fatal("matt: awaitDownloadIfPending called, downloadFuture.isDone={}", downloadFuture.isDone());
        if (!downloadFuture.isDone()) {
            log.info("Waiting for WRAPS proving key download to complete");
            try {
                downloadFuture.get(downloadTimeout.toMillis(), TimeUnit.MILLISECONDS);
                log.fatal("matt: download future completed after waiting");
            } catch (final TimeoutException e) {
                throw new IllegalStateException(
                        "WRAPS proving key download did not complete within " + downloadTimeout, e);
            } catch (final ExecutionException e) {
                log.fatal(
                        "matt: download future completed exceptionally: {}",
                        e.getCause().getMessage());
                throw (e.getCause() instanceof RuntimeException re) ? re : new IllegalStateException(e.getCause());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for WRAPS proving key download", e);
            }
        } else {
            log.fatal("matt: download future already done, joining");
            downloadFuture.join();
            log.fatal("matt: join completed");
        }
    }

    private static Bytes hashFile(@NonNull final Path path) {
        try {
            return CommonUtils.noThrowSha384HashOf(Bytes.wrap(Files.readAllBytes(path)));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read WRAPS proving key file at " + path, e);
        }
    }
}
