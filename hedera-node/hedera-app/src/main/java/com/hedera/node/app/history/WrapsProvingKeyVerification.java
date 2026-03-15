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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Encapsulates the WRAPS proving key hash verification lifecycle.
 *
 * <p>During {@code onStateInitialized()}, the on-disk proving key file is verified
 * against the bootstrap hash from config. The hash is persisted to state immediately
 * during {@code ensureProvingKey()}, which runs on all init triggers (genesis, restart,
 * reconnect, event stream recovery).
 *
 * <p>If the file is missing or its hash does not match, the file is downloaded from
 * the configured URL. On download failure or hash mismatch after download, the error
 * is logged and a recurring retry is scheduled. The node continues startup regardless.
 *
 * <p>After successful hash verification, the proving key archive (.tar.gz) is extracted
 * to the parent directory of the proving key path.
 */
public class WrapsProvingKeyVerification {
    private static final Logger log = LogManager.getLogger(WrapsProvingKeyVerification.class);

    public static final int READ_BUFFER_SIZE = 50 * 1024 * 1024; // ~50 MB

    // Tar extraction security limits (aligned with UnzipUtility)
    private static final int MAX_TAR_ENTRIES = 10_000;
    private static final long MAX_TAR_TOTAL_BYTES = 10L * 1024L * 1024L * 1024L; // 10 GiB
    private static final long MAX_TAR_ENTRY_BYTES = 2L * 1024L * 1024L * 1024L; // 2 GiB
    private static final int MAX_TAR_DEPTH = 20;
    private static final int MAX_TAR_NAME_LENGTH = 4096;
    private static final int EXTRACT_BUFFER_SIZE = 8192;

    private final Executor downloadExecutor;

    @Nullable
    private final ScheduledExecutorService retryScheduler;

    @Nullable
    private volatile ScheduledFuture<?> retryFuture;

    public WrapsProvingKeyVerification() {
        this(ForkJoinPool.commonPool(), createDefaultRetryScheduler());
    }

    public WrapsProvingKeyVerification(@NonNull final Executor downloadExecutor) {
        this(downloadExecutor, null);
    }

    public WrapsProvingKeyVerification(
            @NonNull final Executor downloadExecutor,
            @Nullable final ScheduledExecutorService retryScheduler) {
        this.downloadExecutor = requireNonNull(downloadExecutor);
        this.retryScheduler = retryScheduler;
    }

    /**
     * Ensures the WRAPS proving key is set up: persists the hash to state,
     * verifies the on-disk file, and kicks off a download if the file is
     * missing or corrupt.
     *
     * @param state the current state
     * @param config the configuration
     * @param downloader the downloader to invoke if the file is missing or corrupt
     */
    public void ensureProvingKey(
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

        final var expectedHash = Bytes.fromHex(bootstrapHash);
        log.info("WRAPS proving key hash from config: {}", expectedHash);

        // Persist the hash to state immediately (before kicking off any download)
        persistHashToState(state, expectedHash);

        final var provingKeyPath = Paths.get(tssConfig.wrapsProvingKeyPath());
        final var downloadUrl = tssConfig.wrapsProvingKeyDownloadUrl();
        final var retryInterval = tssConfig.wrapsProvingKeyRetryInterval();
        verifyFileAndDownloadIfNeeded(provingKeyPath, bootstrapHash, downloadUrl, downloader, retryInterval);
    }

    private void persistHashToState(@NonNull final State state, @NonNull final Bytes expectedHash) {
        final var historyStates = state.getWritableStates(HistoryService.NAME);
        final var historyStore = new WritableHistoryStoreImpl(historyStates);
        final var currProvingKeyHash = historyStore.getWrapsProvingKeyHash();
        if (currProvingKeyHash == null) {
            historyStore.setWrapsProvingKeyHash(expectedHash);
            ((CommittableWritableStates) historyStates).commit();
            log.info("Persisted first WRAPS proving key hash {} to state", expectedHash);
        } else if (currProvingKeyHash.equals(expectedHash)) {
            log.info("WRAPS proving key hash {} matches proving key in state", expectedHash);
        } else {
            log.info(
                    "Overwriting previous WRAPS proving key hash {} with new hash {}",
                    currProvingKeyHash,
                    expectedHash);
            historyStore.setWrapsProvingKeyHash(expectedHash);
            ((CommittableWritableStates) historyStates).commit();
        }
    }

    private void verifyFileAndDownloadIfNeeded(
            @NonNull final Path provingKeyPath,
            @NonNull final String bootstrapHash,
            @NonNull final String downloadUrl,
            @NonNull final HttpWrapsProvingKeyDownloader downloader,
            @NonNull final Duration retryInterval) {
        final var expectedHash = Bytes.fromHex(bootstrapHash);
        if (!Files.exists(provingKeyPath)) {
            log.info("WRAPS proving key file not found at {}. Initiating download", provingKeyPath);
            asyncDownloadAndVerify(provingKeyPath, expectedHash, downloadUrl, downloader, retryInterval);
            return;
        }
        final Bytes fileHash = hashFile(provingKeyPath);
        if (!fileHash.equals(expectedHash)) {
            log.warn(
                    "WRAPS proving key hash mismatch at {} (expected={}, actual={}), initiating download",
                    provingKeyPath,
                    expectedHash,
                    fileHash);
            asyncDownloadAndVerify(provingKeyPath, expectedHash, downloadUrl, downloader, retryInterval);
            return;
        }
        // Hash matches - extract the archive
        tryExtractTarGz(provingKeyPath);
    }

    private void asyncDownloadAndVerify(
            @NonNull final Path provingKeyPath,
            @NonNull final Bytes expectedHash,
            @NonNull final String downloadUrl,
            @NonNull final HttpWrapsProvingKeyDownloader downloader,
            @NonNull final Duration retryInterval) {
        CompletableFuture.runAsync(
                () -> {
                    try {
                        downloader.download(downloadUrl, provingKeyPath);
                    } catch (final IOException e) {
                        log.error("Failed to download WRAPS proving key from {}", downloadUrl, e);
                        scheduleRetry(provingKeyPath, expectedHash, downloadUrl, downloader, retryInterval);
                        return;
                    }
                    final Bytes downloadedHash = hashFile(provingKeyPath);
                    if (!downloadedHash.equals(expectedHash)) {
                        log.error(
                                "Downloaded WRAPS proving key hash mismatch: expected={}, actual={}",
                                expectedHash,
                                downloadedHash);
                        scheduleRetry(provingKeyPath, expectedHash, downloadUrl, downloader, retryInterval);
                        return;
                    }
                    tryExtractTarGz(provingKeyPath);
                    log.info("Successfully downloaded and verified WRAPS proving key (hash={})", expectedHash);
                },
                downloadExecutor);
    }

    // --- Retry mechanism ---

    private void scheduleRetry(
            @NonNull final Path provingKeyPath,
            @NonNull final Bytes expectedHash,
            @NonNull final String downloadUrl,
            @NonNull final HttpWrapsProvingKeyDownloader downloader,
            @NonNull final Duration retryInterval) {
        if (retryScheduler == null || retryFuture != null) {
            return;
        }
        log.info("Scheduling WRAPS proving key download retry every {}", retryInterval);
        retryFuture = retryScheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        log.info("Retrying WRAPS proving key download from {}", downloadUrl);
                        downloader.download(downloadUrl, provingKeyPath);
                        final Bytes downloadedHash = hashFile(provingKeyPath);
                        if (downloadedHash.equals(expectedHash)) {
                            tryExtractTarGz(provingKeyPath);
                            log.info(
                                    "Successfully downloaded and verified WRAPS proving key on retry (hash={})",
                                    expectedHash);
                            cancelRetry();
                        } else {
                            log.error(
                                    "Downloaded WRAPS proving key hash mismatch on retry: expected={}, actual={}",
                                    expectedHash,
                                    downloadedHash);
                        }
                    } catch (final Exception e) {
                        log.error("Failed to download WRAPS proving key on retry", e);
                    }
                },
                retryInterval.toMillis(),
                retryInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void cancelRetry() {
        final var future = retryFuture;
        if (future != null) {
            future.cancel(false);
            retryFuture = null;
        }
    }

    // --- Tar.gz extraction ---

    private static void tryExtractTarGz(@NonNull final Path tarGzPath) {
        try {
            extractTarGz(tarGzPath, tarGzPath.getParent());
            log.info("Extracted WRAPS proving key archive {} to {}", tarGzPath, tarGzPath.getParent());
        } catch (final IOException e) {
            log.error("Failed to extract WRAPS proving key archive {}", tarGzPath, e);
        }
    }

    static void extractTarGz(@NonNull final Path tarGzPath, @NonNull final Path destDir) throws IOException {
        requireNonNull(tarGzPath);
        requireNonNull(destDir);
        final Path resolvedDestDir = destDir.toAbsolutePath().normalize();
        final Path canonicalDestDir =
                resolvedDestDir.toFile().getCanonicalFile().toPath();

        try (var fis = new FileInputStream(tarGzPath.toFile());
                var gis = new GZIPInputStream(fis);
                var tis = new TarArchiveInputStream(gis)) {
            TarArchiveEntry entry;
            int entryCount = 0;
            long totalExtractedBytes = 0L;

            while ((entry = tis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_TAR_ENTRIES) {
                    throw new IOException("Tar archive contains too many entries (>" + MAX_TAR_ENTRIES + ")");
                }

                final String entryName = entry.getName();
                validateTarEntryName(entryName);

                final int depth = Path.of(entryName).normalize().getNameCount();
                if (depth > MAX_TAR_DEPTH) {
                    throw new IOException("Tar entry nested too deeply (depth=" + depth + ", max=" + MAX_TAR_DEPTH
                            + "): " + entryName);
                }

                // Reject symbolic and hard links for security
                if (entry.isSymbolicLink() || entry.isLink()) {
                    log.warn("Skipping symbolic/hard link in tar archive: {}", entryName);
                    continue;
                }

                final Path filePath = resolvedDestDir.resolve(entryName).normalize();
                if (!filePath.startsWith(resolvedDestDir)) {
                    throw new IOException("Tar entry is outside destination directory: " + entryName);
                }

                final Path canonicalPath = filePath.toFile().getCanonicalFile().toPath();
                if (!canonicalPath.startsWith(canonicalDestDir)) {
                    throw new IOException("Tar entry is outside destination directory (symlink): " + entryName);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(filePath);
                } else {
                    Files.createDirectories(filePath.getParent());
                    final long remaining = MAX_TAR_TOTAL_BYTES - totalExtractedBytes;
                    totalExtractedBytes += extractTarEntry(tis, filePath, MAX_TAR_ENTRY_BYTES, remaining);
                }
            }
        }
    }

    private static long extractTarEntry(
            @NonNull final TarArchiveInputStream tis,
            @NonNull final Path filePath,
            final long maxEntryBytes,
            final long remainingTotalBytes)
            throws IOException {
        long written = 0L;
        try (var bos = new BufferedOutputStream(new FileOutputStream(filePath.toFile()))) {
            final var buffer = new byte[EXTRACT_BUFFER_SIZE];
            int read;
            while ((read = tis.read(buffer)) != -1) {
                if (written + read > maxEntryBytes) {
                    throw new IOException("Tar entry exceeds max size (" + maxEntryBytes + " bytes): " + filePath);
                }
                if (written + read > remainingTotalBytes) {
                    throw new IOException(
                            "Tar archive exceeds max total extracted size (" + MAX_TAR_TOTAL_BYTES + " bytes)");
                }
                bos.write(buffer, 0, read);
                written += read;
            }
        } catch (final IOException e) {
            // Best-effort cleanup of partially written output
            try {
                final var f = filePath.toFile();
                if (f.exists() && !f.delete()) {
                    log.warn("Unable to delete partially extracted file {}", filePath);
                }
            } catch (final Exception ignored) {
                // ignore cleanup failures
            }
            throw e;
        }
        return written;
    }

    private static void validateTarEntryName(@NonNull final String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("Tar entry has an empty name");
        }
        if (name.length() > MAX_TAR_NAME_LENGTH) {
            throw new IOException("Tar entry name is too long (>" + MAX_TAR_NAME_LENGTH + " chars)");
        }
        // Reject null bytes and control characters
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == 0 || Character.isISOControl(c)) {
                throw new IOException("Tar entry name contains a control character");
            }
        }
        // Reject absolute paths and Windows drive-letter paths
        if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:[\\\\/].*")) {
            throw new IOException("Tar entry name is an absolute path");
        }
        // Reject backslashes to avoid platform-specific traversal quirks
        if (name.indexOf('\\') != -1) {
            throw new IOException("Tar entry name contains invalid path separator");
        }
        // Reject obvious traversal attempts
        if (name.equals("..") || name.startsWith("../") || name.contains("/../") || name.endsWith("/..")) {
            throw new IOException("Tar entry name contains path traversal");
        }
    }

    // --- File hashing ---

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

    private static ScheduledExecutorService createDefaultRetryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            final var t = new Thread(r, "wraps-proving-key-retry");
            t.setDaemon(true);
            return t;
        });
    }
}
