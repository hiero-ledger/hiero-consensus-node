// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.history.impl.WritableHistoryStoreImpl;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
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
 * Encapsulates the WRAPS proving key hash verification lifecycle.
 *
 * <p>During {@code onStateInitialized()}, the on-disk proving key file is verified
 * against the bootstrap hash from config. If the hash matches and state does not yet
 * contain it, the hash is held as pending. Later, during {@code doPostUpgradeSetup()},
 * the pending hash is persisted to state.
 */
public class WrapsProvingKeyVerification {
    private static final Logger log = LogManager.getLogger(WrapsProvingKeyVerification.class);

    private static final Duration DEFAULT_DOWNLOAD_TIMEOUT = Duration.ofSeconds(300);
    public static final int READ_BUFFER_SIZE = 50 * 1024 * 1024; // ~50 MB

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
     * @param config the configuration
     * @param downloader the downloader to invoke if the file is missing or corrupt
     */
    public void verify(
            @NonNull final State state,
            @NonNull final Configuration config,
            @NonNull final HttpWrapsProvingKeyDownloader downloader) {
        requireNonNull(state);
        requireNonNull(config);
        requireNonNull(downloader);
        final var tssConfig = config.getConfigData(TssConfig.class);
        if (!tssConfig.wrapsEnabled()) {
            log.info("WRAPS not enabled, skipping proving key hash verification");
            return;
        }

        final var bootstrapHash = tssConfig.wrapsProvingKeyHash();
        if (bootstrapHash.isBlank()) {
            throw new IllegalArgumentException("WRAPS proving key hash is required");
        }

        final var provingKeyPath = Paths.get(tssConfig.wrapsProvingKeyPath());
        final var downloadUrl = tssConfig.wrapsProvingKeyDownloadUrl();
        verifyAndRememberPendingHash(provingKeyPath, bootstrapHash, downloadUrl, downloader);
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
            @NonNull final String downloadUrl,
            @NonNull final HttpWrapsProvingKeyDownloader downloader) {
        final var expectedHash = Bytes.fromHex(bootstrapHash);
        if (!Files.exists(provingKeyPath)) {
            log.info("WRAPS proving key file not found at {}. Initiating download", provingKeyPath);
            asyncDownloadAndVerify(provingKeyPath, expectedHash, downloadUrl, downloader);
            return;
        }
        final Bytes fileHash = hashFile(provingKeyPath);
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
    }

    private void asyncDownloadAndVerify(
            @NonNull final Path provingKeyPath,
            @NonNull final Bytes expectedHash,
            @NonNull final String downloadUrl,
            @NonNull final HttpWrapsProvingKeyDownloader downloader) {
        downloadFuture = CompletableFuture.runAsync(
                () -> {
                    try {
                        downloader.download(downloadUrl, provingKeyPath);
                    } catch (final IOException e) {
                        log.error("Failed to download WRAPS proving key from {}", downloadUrl, e);
                        return;
                    }
                    final Bytes downloadedHash = hashFile(provingKeyPath);
                    if (!downloadedHash.equals(expectedHash)) {
                        throw new IllegalStateException("Downloaded WRAPS proving key hash mismatch: expected="
                                + expectedHash + ", actual=" + downloadedHash);
                    }
                    this.pendingHash = expectedHash;
                    log.info("Successfully downloaded and verified WRAPS proving key (hash={})", expectedHash);
                },
                downloadExecutor);
    }

    private void awaitDownloadIfPending() {
        if (!downloadFuture.isDone()) {
            log.info("Waiting for WRAPS proving key download to complete");
            try {
                downloadFuture.get(downloadTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (final TimeoutException e) {
                throw new IllegalStateException(
                        "WRAPS proving key download did not complete within " + downloadTimeout, e);
            } catch (final ExecutionException e) {
                throw (e.getCause() instanceof RuntimeException re) ? re : new IllegalStateException(e.getCause());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for WRAPS proving key download", e);
            }
        } else {
            downloadFuture.join();
        }
    }

    private static Bytes hashFile(@NonNull final Path path) {
        requireNonNull(path);

        try {
            final MessageDigest digest = CommonUtils.sha384DigestOrThrow();
            // We expect these files to be large, so allocate a large buffer
            final byte[] buffer = new byte[READ_BUFFER_SIZE];
            try (final FileInputStream fileInputStream = new FileInputStream(path.toFile())) {
                int bytesRead;
                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            return Bytes.wrap(digest.digest());
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read WRAPS proving key file at " + path, e);
        }
    }
}
