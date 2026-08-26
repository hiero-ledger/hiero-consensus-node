// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Objects.requireNonNull;

import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * to the directory specified by the {@code TSS_LIB_WRAPS_ARTIFACTS_PATH} environment variable.
 * The {@code tss.wrapsProvingKeyPath} config controls only where the archive file is stored on disk.
 *
 * <p>The archive is extracted into a staging subdirectory first and only then published onto the
 * artifacts directory, one atomic rename per file. The native library maps these artifacts, and
 * rewriting one in place truncates an inode a running proof may already have mapped, which faults
 * with {@code SIGBUS}; a rename leaves that inode intact.
 *
 * <p>To avoid re-downloading and re-extracting the multi-gigabyte archive on every startup when the
 * extracted artifacts are already present (e.g. mounted from the published data-only image), a
 * hash file ({@value #WRAPS_HASH_FILE_NAME}) is consulted in the artifacts directory before any
 * download. If its hash matches {@code tss.wrapsProvingKeyHash} and the required artifacts are
 * present, the download and extraction are skipped entirely. After a successful extraction the hash
 * file is (re)written so subsequent startups can short-circuit. (This hash file is unrelated to
 * record-stream sidecar files.)
 */
public class WrapsProvingKeyVerification {
    private static final Logger log = LogManager.getLogger(WrapsProvingKeyVerification.class);

    static final String WRAPS_ARTIFACTS_ENV_VAR = "TSS_LIB_WRAPS_ARTIFACTS_PATH";

    /**
     * Name of the hash file written into the {@code TSS_LIB_WRAPS_ARTIFACTS_PATH} directory that
     * holds the SHA-384 hash (bare lowercase hex) of the proving key archive the artifacts were
     * extracted from. Its presence and value let a node skip re-downloading when the artifacts are
     * already in place. Must match the file produced by the published proving-key image build.
     */
    static final String WRAPS_HASH_FILE_NAME = "wraps.sha384";

    /**
     * Name of the per-file artifact manifest written into the {@code TSS_LIB_WRAPS_ARTIFACTS_PATH}
     * directory. Each line is {@code <sha384hex>  <filename>} (sha384sum(1) format) covering the
     * four required artifact files. Written by both the published image build and the CN after a
     * successful extraction, so that {@link #artifactsAlreadyPresent} can detect a corrupt or
     * incomplete installation.
     */
    static final String WRAPS_ARTIFACTS_MANIFEST_FILE_NAME = "wraps-artifacts.sha384";

    /**
     * Name of the staging subdirectory the archive is extracted into before its contents are published
     * onto the artifacts directory. Kept inside the artifacts directory so that publishing is a
     * same-filesystem rename; a sibling directory could land on a different mount.
     */
    static final String STAGING_DIR_NAME = ".wraps-staging";

    public static final int READ_BUFFER_SIZE = 50 * 1024 * 1024; // ~50 MB
    static final Set<String> REQUIRED_ARTIFACT_FILES =
            Set.of("decider_pp.bin", "decider_vp.bin", "nova_pp.bin", "nova_vp.bin");

    private final Executor downloadExecutor;

    @Nullable
    private final ScheduledExecutorService retryScheduler;

    @Nullable
    private volatile ScheduledFuture<?> retryFuture;

    /**
     * Guards against more than one acquisition attempt at a time, covering both the download and the extraction
     * that follows it. {@link #ensureProvingKey} runs on every init trigger, so without this a node that keeps
     * reconnecting while the archive is unavailable stacks a download per reconnect on the shared
     * {@link ForkJoinPool#commonPool()}, and the concurrent attempts truncate each other's output at
     * {@code provingKeyPath}.
     */
    private final AtomicBoolean downloadInFlight = new AtomicBoolean();

    public WrapsProvingKeyVerification() {
        this(ForkJoinPool.commonPool(), createDefaultRetryScheduler());
    }

    public WrapsProvingKeyVerification(@NonNull final Executor downloadExecutor) {
        this(downloadExecutor, null);
    }

    public WrapsProvingKeyVerification(
            @NonNull final Executor downloadExecutor, @Nullable final ScheduledExecutorService retryScheduler) {
        this.downloadExecutor = requireNonNull(downloadExecutor);
        this.retryScheduler = retryScheduler;
    }

    /**
     * Ensures the WRAPS proving key is set up: verifies the on-disk file, and kicks off a download
     * if the file is missing or corrupt.
     *
     * @param config the configuration
     * @param downloader the downloader to invoke if the file is missing or corrupt
     */
    public void ensureProvingKey(
            @NonNull final Configuration config, @NonNull final HttpWrapsProvingKeyDownloader downloader) {
        requireNonNull(config);
        requireNonNull(downloader);
        final var tssConfig = config.getConfigData(TssConfig.class);
        if (!tssConfig.wrapsProvingKeyDownloadEnabled()) {
            log.info("WRAPS proving key download not enabled, skipping proving key hash verification");
            return;
        }

        final var bootstrapHash = tssConfig.wrapsProvingKeyHash();
        if (bootstrapHash.isBlank()) {
            throw new IllegalArgumentException("WRAPS proving key hash is required");
        }
        final var expectedHash = Bytes.fromHex(bootstrapHash);
        log.info("WRAPS proving key hash from config: {}", expectedHash);

        final var envArtifactsPath = System.getenv(WRAPS_ARTIFACTS_ENV_VAR);
        if (envArtifactsPath == null || envArtifactsPath.isBlank()) {
            log.error("{} environment variable is not set; cannot verify WRAPS proving key", WRAPS_ARTIFACTS_ENV_VAR);
            return;
        }
        final var provingKeyPath = Paths.get(tssConfig.wrapsProvingKeyPath());
        validateArtifactsPathConsistency(provingKeyPath, envArtifactsPath);

        // If the extracted artifacts are already in place with a hash file matching config, there is
        // nothing to download or extract (e.g. the artifacts directory is mounted from the published image).
        if (artifactsAlreadyPresent(envArtifactsPath, bootstrapHash)) {
            log.info(
                    "WRAPS artifacts already present in {} with matching {} hash; skipping download and extraction",
                    envArtifactsPath,
                    WRAPS_HASH_FILE_NAME);
            return;
        }

        final var downloadUrl = tssConfig.wrapsProvingKeyDownloadUrl();
        final var retryInterval = tssConfig.wrapsProvingKeyRetryInterval();
        verifyFileAndDownloadIfNeeded(provingKeyPath, bootstrapHash, downloadUrl, downloader, retryInterval);
    }

    /**
     * Determines whether the extracted WRAPS artifacts are already present in the artifacts directory
     * and up to date, so that the archive download and extraction can be skipped. Logs the reason when
     * they are not.
     *
     * @param envArtifactsPath the artifacts directory path, or null/blank if unset
     * @param expectedHashHex the expected archive hash (bare hex) from {@code tss.wrapsProvingKeyHash}
     * @return true if the artifacts are already present and match; false if a download is needed
     */
    static boolean artifactsAlreadyPresent(
            @Nullable final String envArtifactsPath, @NonNull final String expectedHashHex) {
        final var defect = installationDefect(envArtifactsPath, expectedHashHex);
        if (defect != null) {
            log.info("Not skipping WRAPS download and extraction for {}: {}", envArtifactsPath, defect);
            return false;
        }
        return true;
    }

    /**
     * Returns whether the WRAPS artifacts installed in {@code TSS_LIB_WRAPS_ARTIFACTS_PATH} are complete
     * and verified against the given archive hash, so that native code may safely map them.
     *
     * <p>This holds the installation to the same bar as {@link #artifactsAlreadyPresent}: merely finding
     * the four {@code *.bin} filenames is not enough, because they may be a different proving key, or the
     * partially published output of an install still in flight. Unlike {@code artifactsAlreadyPresent}
     * this is quiet, since it is consulted once per consensus round.
     *
     * @param expectedHashHex the expected archive hash (bare hex) from {@code tss.wrapsProvingKeyHash}
     * @return true if the installed artifacts are complete and match the expected hash
     */
    public static boolean artifactsInstalledAndVerified(@NonNull final String expectedHashHex) {
        requireNonNull(expectedHashHex);
        return installationDefect(System.getenv(WRAPS_ARTIFACTS_ENV_VAR), expectedHashHex) == null;
    }

    /**
     * Returns a human-readable description of why the artifacts directory does not hold a complete
     * installation of the expected proving key, or null if it does.
     */
    @Nullable
    private static String installationDefect(
            @Nullable final String envArtifactsPath, @NonNull final String expectedHashHex) {
        if (envArtifactsPath == null || envArtifactsPath.isBlank()) {
            return WRAPS_ARTIFACTS_ENV_VAR + " is not set";
        }
        final var artifactsDir = Paths.get(envArtifactsPath);
        final var hashFile = artifactsDir.resolve(WRAPS_HASH_FILE_NAME);
        if (!Files.isRegularFile(hashFile)) {
            return "hash file " + hashFile + " is missing";
        }
        final String storedHash;
        try {
            storedHash = Files.readString(hashFile).trim();
        } catch (final IOException e) {
            return "hash file " + hashFile + " could not be read (" + e.getMessage() + ")";
        }
        if (!storedHash.equalsIgnoreCase(expectedHashHex.trim())) {
            return "hash file " + hashFile + " (" + storedHash + ") does not match the configured hash ("
                    + expectedHashHex + ")";
        }
        final var missingArtifacts = REQUIRED_ARTIFACT_FILES.stream()
                .filter(name -> !Files.isRegularFile(artifactsDir.resolve(name)))
                .toList();
        if (!missingArtifacts.isEmpty()) {
            return "artifacts " + missingArtifacts + " are missing from " + artifactsDir;
        }
        // If a manifest is present, verify it lists all required artifacts. An absent manifest is
        // accepted (e.g. an older image without the manifest file) to preserve backwards compatibility
        // with read-only mounts that cannot be updated.
        final var manifestFile = artifactsDir.resolve(WRAPS_ARTIFACTS_MANIFEST_FILE_NAME);
        if (Files.isRegularFile(manifestFile) && !manifestListsAllArtifacts(manifestFile)) {
            return "artifacts manifest " + manifestFile + " is incomplete or unreadable";
        }
        return null;
    }

    private void verifyFileAndDownloadIfNeeded(
            @NonNull final Path provingKeyPath,
            @NonNull final String bootstrapHash,
            @NonNull final String downloadUrl,
            @NonNull final HttpWrapsProvingKeyDownloader downloader,
            @NonNull final Duration retryInterval) {
        // An in-flight attempt verifies and extracts the archive itself, so there is nothing to do here. Checked
        // before hashing, which reads a multi-gigabyte file on the init thread.
        if (downloadInFlight.get()) {
            log.info("A WRAPS proving key acquisition is already in progress, skipping this check");
            return;
        }
        final var expectedHash = Bytes.fromHex(bootstrapHash);
        if (!Files.exists(provingKeyPath)) {
            log.info("WRAPS proving key file not found at {}. Initiating download", provingKeyPath);
            asyncDownloadAndVerify(provingKeyPath, expectedHash, downloadUrl, downloader, retryInterval);
            return;
        }
        final Bytes fileHash;
        try {
            fileHash = hashFile(provingKeyPath);
        } catch (final UncheckedIOException e) {
            log.warn("Failed to read WRAPS proving key file at {}; initiating download", provingKeyPath, e);
            asyncDownloadAndVerify(provingKeyPath, expectedHash, downloadUrl, downloader, retryInterval);
            return;
        }
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
        tryExtractTarGz(provingKeyPath, expectedHash.toHex());
    }

    private void asyncDownloadAndVerify(
            @NonNull final Path provingKeyPath,
            @NonNull final Bytes expectedHash,
            @NonNull final String downloadUrl,
            @NonNull final HttpWrapsProvingKeyDownloader downloader,
            @NonNull final Duration retryInterval) {
        if (!downloadInFlight.compareAndSet(false, true)) {
            log.info("A WRAPS proving key download is already in progress, not starting another");
            return;
        }
        try {
            CompletableFuture.runAsync(
                    () -> {
                        try {
                            downloader.download(downloadUrl, provingKeyPath);
                            final Bytes downloadedHash = hashFile(provingKeyPath);
                            if (!downloadedHash.equals(expectedHash)) {
                                log.error(
                                        "Downloaded WRAPS proving key hash mismatch: expected={}, actual={}",
                                        expectedHash,
                                        downloadedHash);
                                scheduleRetry(provingKeyPath, expectedHash, downloadUrl, downloader, retryInterval);
                                return;
                            }
                            tryExtractTarGz(provingKeyPath, expectedHash.toHex());
                            log.info("Successfully downloaded and verified WRAPS proving key (hash={})", expectedHash);
                        } catch (final Throwable t) {
                            log.error(
                                    "Failed to initiate async download of WRAPS proving key (from URL {}):",
                                    downloadUrl,
                                    t);
                            scheduleRetry(provingKeyPath, expectedHash, downloadUrl, downloader, retryInterval);
                        } finally {
                            downloadInFlight.set(false);
                        }
                    },
                    downloadExecutor);
        } catch (final RuntimeException e) {
            // The task never ran, so nothing else will clear the flag
            downloadInFlight.set(false);
            throw e;
        }
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
                    if (!downloadInFlight.compareAndSet(false, true)) {
                        log.info("A WRAPS proving key download is already in progress, skipping this retry");
                        return;
                    }
                    try {
                        log.info("Retrying WRAPS proving key download from {}", downloadUrl);
                        downloader.download(downloadUrl, provingKeyPath);
                        final Bytes downloadedHash = hashFile(provingKeyPath);
                        if (downloadedHash.equals(expectedHash)) {
                            tryExtractTarGz(provingKeyPath, expectedHash.toHex());
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
                    } catch (final Throwable e) {
                        log.error("Failed to download WRAPS proving key on retry", e);
                    } finally {
                        downloadInFlight.set(false);
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

    // --- Artifacts path validation ---

    /**
     * Validates that the {@code TSS_LIB_WRAPS_ARTIFACTS_PATH} environment variable (which the native
     * WRAPS library reads to locate unpacked artifacts) is consistent with the extraction directory
     * derived from {@code tss.wrapsProvingKeyPath} (the packed tar file). Relative paths are resolved
     * against the working directory before comparison, so the default relative
     * {@code tss.wrapsProvingKeyPath} is compatible with an absolute env var value.
     *
     * @param provingKeyPath the configured path to the packed proving key archive
     * @param envArtifactsPath the value of the {@code TSS_LIB_WRAPS_ARTIFACTS_PATH} env var, or null
     * @throws IllegalStateException if the env var is set but points outside the extraction directory
     */
    static void validateArtifactsPathConsistency(
            @NonNull final Path provingKeyPath, @Nullable final String envArtifactsPath) {
        if (envArtifactsPath == null || envArtifactsPath.isBlank()) {
            log.warn(
                    "{} environment variable is not set; native WRAPS library may not find extracted artifacts",
                    WRAPS_ARTIFACTS_ENV_VAR);
            return;
        }
        final var extractionTarget = provingKeyPath.getParent();
        if (extractionTarget == null) {
            log.warn(
                    "Proving key path {} has no parent directory; cannot validate {} environment variable consistency",
                    provingKeyPath,
                    WRAPS_ARTIFACTS_ENV_VAR);
            return;
        }
        final var envPath = Paths.get(envArtifactsPath).toAbsolutePath().normalize();
        final var normalizedTarget = extractionTarget.toAbsolutePath().normalize();
        if (!envPath.startsWith(normalizedTarget)) {
            throw new IllegalStateException(WRAPS_ARTIFACTS_ENV_VAR + " (" + envArtifactsPath
                    + ") is not under the extraction directory (" + normalizedTarget
                    + ") derived from tss.wrapsProvingKeyPath (" + provingKeyPath + ")");
        }
    }

    // --- Tar.gz extraction ---

    private static void tryExtractTarGz(@NonNull final Path tarGzPath, @NonNull final String expectedHashHex) {
        final var envArtifactsPath = System.getenv(WRAPS_ARTIFACTS_ENV_VAR);
        if (envArtifactsPath == null || envArtifactsPath.isBlank()) {
            log.warn(
                    "Cannot extract WRAPS proving key archive; {} environment variable is not set",
                    WRAPS_ARTIFACTS_ENV_VAR);
            return;
        }
        final var extractionDir = Paths.get(envArtifactsPath);
        final var stagingDir = extractionDir.resolve(STAGING_DIR_NAME);
        try {
            Files.createDirectories(extractionDir);
            deleteRecursively(stagingDir);
            Files.createDirectories(stagingDir);
            TarGzExtractor.extract(tarGzPath, stagingDir);
            log.info("Extracted WRAPS proving key archive {} to staging directory {}", tarGzPath, stagingDir);
            final var missingArtifacts = REQUIRED_ARTIFACT_FILES.stream()
                    .filter(name -> !Files.isRegularFile(stagingDir.resolve(name)))
                    .toList();
            if (!missingArtifacts.isEmpty()) {
                log.error(
                        "WRAPS proving key archive {} did not yield required artifacts {}; leaving the "
                                + "installed artifacts in {} untouched",
                        tarGzPath,
                        missingArtifacts,
                        extractionDir);
                return;
            }
            publishStagedArtifacts(stagingDir, extractionDir, expectedHashHex);
            log.info("Installed WRAPS proving key artifacts from {} into {}", tarGzPath, extractionDir);
            verifyArtifactsDirectoryExists();
        } catch (final IOException e) {
            log.error("Failed to install WRAPS proving key archive {}", tarGzPath, e);
        } finally {
            try {
                deleteRecursively(stagingDir);
            } catch (final IOException e) {
                log.warn("Failed to clean up WRAPS staging directory {}", stagingDir, e);
            }
        }
    }

    /**
     * Publishes freshly extracted artifacts from the staging directory onto the live artifacts directory.
     *
     * <p>Each file is moved with {@link StandardCopyOption#ATOMIC_MOVE}, which replaces the directory entry
     * without touching the previous inode. A proof already running against the old artifacts therefore keeps
     * a valid mapping, rather than faulting with {@code SIGBUS} the way an in-place rewrite does when it
     * truncates a file the native library has mapped.
     *
     * <p>The readiness markers are removed first: while the moves are in flight the directory holds a mix of
     * old and new files, and {@code HistoryLibrary.wrapsProverReady} must not admit a proof against it. The
     * hash file is written last, so its presence implies a complete installation.
     */
    private static void publishStagedArtifacts(
            @NonNull final Path stagingDir, @NonNull final Path extractionDir, @NonNull final String expectedHashHex)
            throws IOException {
        Files.deleteIfExists(extractionDir.resolve(WRAPS_HASH_FILE_NAME));
        Files.deleteIfExists(extractionDir.resolve(WRAPS_ARTIFACTS_MANIFEST_FILE_NAME));
        final List<Path> stagedFiles;
        try (final var paths = Files.walk(stagingDir)) {
            stagedFiles = paths.filter(Files::isRegularFile).toList();
        }
        for (final var source : stagedFiles) {
            final var target = extractionDir.resolve(stagingDir.relativize(source));
            final var targetParent = target.getParent();
            if (targetParent != null) {
                Files.createDirectories(targetParent);
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        writeArtifactsManifest(extractionDir);
        writeHashFile(extractionDir, expectedHashHex);
    }

    /**
     * Deletes the given directory tree if it exists, deepest entries first.
     */
    private static void deleteRecursively(@NonNull final Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        final List<Path> entries;
        try (final var paths = Files.walk(dir)) {
            entries = paths.sorted(Comparator.reverseOrder()).toList();
        }
        for (final var entry : entries) {
            Files.deleteIfExists(entry);
        }
    }

    /**
     * Writes the hash file ({@value #WRAPS_HASH_FILE_NAME}) into the artifacts directory so that
     * subsequent startups can detect the artifacts are already present and skip the download/extraction.
     * A failure to write (e.g. a read-only mount) is logged but is non-fatal: the extracted artifacts
     * remain usable.
     *
     * @param extractionDir the directory the artifacts were extracted into
     * @param hashHex the archive hash (bare hex) to record
     */
    private static void writeHashFile(@NonNull final Path extractionDir, @NonNull final String hashHex) {
        final var hashFile = extractionDir.resolve(WRAPS_HASH_FILE_NAME);
        try {
            Files.writeString(hashFile, hashHex);
            log.info("Wrote WRAPS proving key hash file {} ({})", hashFile, hashHex);
        } catch (final IOException e) {
            log.error("Failed to write WRAPS proving key hash file {}", hashFile, e);
        }
    }

    /**
     * Writes the per-file artifact manifest ({@value #WRAPS_ARTIFACTS_MANIFEST_FILE_NAME}) into the
     * extraction directory. Each line is {@code <sha384hex>  <filename>} (sha384sum(1) format).
     * Written after every successful extraction so subsequent startups can detect an incomplete or
     * corrupt installation via {@link #manifestListsAllArtifacts}. A write failure (e.g. a read-only
     * mount) is logged but non-fatal: the extracted artifacts remain usable.
     */
    private static void writeArtifactsManifest(@NonNull final Path extractionDir) {
        final var manifestPath = extractionDir.resolve(WRAPS_ARTIFACTS_MANIFEST_FILE_NAME);
        final var sb = new StringBuilder();
        for (final var name : REQUIRED_ARTIFACT_FILES) {
            final var filePath = extractionDir.resolve(name);
            if (!Files.isRegularFile(filePath)) {
                log.warn("Skipping missing artifact {} while writing WRAPS manifest", name);
                continue;
            }
            try {
                sb.append(hashFile(filePath).toHex()).append("  ").append(name).append('\n');
            } catch (final UncheckedIOException e) {
                log.warn("Failed to hash artifact {} while writing WRAPS manifest; skipping", name, e);
            }
        }
        try {
            Files.writeString(manifestPath, sb.toString());
            log.info("Wrote WRAPS artifacts manifest {}", manifestPath);
        } catch (final IOException e) {
            log.error("Failed to write WRAPS artifacts manifest {}", manifestPath, e);
        }
    }

    /**
     * Returns {@code true} if the manifest file lists all {@link #REQUIRED_ARTIFACT_FILES}.
     * Parses each {@code <sha384hex>  <filename>} line and collects the filenames; a parse error
     * or missing entry causes the method to return {@code false} so the caller triggers
     * re-extraction.
     */
    private static boolean manifestListsAllArtifacts(@NonNull final Path manifestFile) {
        try {
            final var listed = new HashSet<String>();
            for (final var line : Files.readAllLines(manifestFile)) {
                final int sep = line.indexOf("  ");
                if (sep > 0) {
                    listed.add(line.substring(sep + 2).trim());
                }
            }
            return listed.containsAll(REQUIRED_ARTIFACT_FILES);
        } catch (final IOException e) {
            log.warn("Could not read WRAPS artifacts manifest {}; treating as incomplete", manifestFile, e);
            return false;
        }
    }

    private static void verifyArtifactsDirectoryExists() {
        final var envArtifactsPath = System.getenv(WRAPS_ARTIFACTS_ENV_VAR);
        if (envArtifactsPath != null && !envArtifactsPath.isBlank()) {
            final var artifactsDir = Paths.get(envArtifactsPath);
            if (Files.isDirectory(artifactsDir)) {
                final var missingArtifacts = REQUIRED_ARTIFACT_FILES.stream()
                        .filter(name -> !Files.isRegularFile(artifactsDir.resolve(name)))
                        .toList();
                if (missingArtifacts.isEmpty()) {
                    log.info(
                            "Verified WRAPS artifacts directory at {} contains {}",
                            artifactsDir,
                            REQUIRED_ARTIFACT_FILES);
                } else {
                    log.error(
                            "After extraction, {} ({}) is missing required WRAPS artifact files {}. "
                                    + "Expected at least {} from the WRAPS v1.0.0 artifact set.",
                            WRAPS_ARTIFACTS_ENV_VAR,
                            envArtifactsPath,
                            missingArtifacts,
                            REQUIRED_ARTIFACT_FILES);
                }
            } else {
                log.error(
                        "After extraction, {} ({}) does not exist as a directory. "
                                + "Verify the archive contents match the expected directory structure.",
                        WRAPS_ARTIFACTS_ENV_VAR,
                        envArtifactsPath);
            }
        }
    }

    // --- File hashing ---

    private static Bytes hashFile(@NonNull final Path path) {
        requireNonNull(path);

        try {
            final MessageDigest digest = sha384DigestOrThrow();
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
