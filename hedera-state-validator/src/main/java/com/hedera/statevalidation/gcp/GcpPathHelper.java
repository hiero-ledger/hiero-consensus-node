// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.gcp;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

/**
 * Utility for downloading files and directories from Google Cloud Storage using the {@code gcloud storage}
 * CLI. No GCP SDK dependency is required — authentication and billing are handled by the locally
 * configured {@code gcloud} tool.
 *
 * <p>All public methods that interact with GCS accept an optional {@code billingProject} parameter
 * for requester-pays buckets.
 */
public final class GcpPathHelper {

    private static final String GCLOUD = System.getProperty("os.name", "")
            .toLowerCase().contains("windows") ? "gcloud.cmd" : "gcloud";

    private static final Logger log = LogManager.getLogger(GcpPathHelper.class);

    /** Marker for log statements that should be routed to stdout for user-facing progress. */
    public static final Marker CONSOLE = MarkerManager.getMarker("CONSOLE");

    /** Prefix that identifies a GCS URI. */
    private static final String GCS_PREFIX = "gs://";

    /** Number of digits in a block file name (before the extension). */
    private static final int BLOCK_NUMBER_DIGITS = 36;

    /** Extension for compressed block files. */
    private static final String BLOCK_FILE_EXTENSION = ".blk.gz";

    /** Maximum number of retries for transient GCP failures. */
    private static final int MAX_RETRIES = 3;

    /** Base delay (in milliseconds) for exponential backoff between retries. */
    private static final long RETRY_BASE_DELAY_MS = 1_000;

    /** Default timeout (in seconds) for a single-file operation. */
    private static final long SINGLE_FILE_TIMEOUT_SECONDS = 60;

    /** Default timeout (in seconds) for a recursive directory download. */
    private static final long DIRECTORY_DOWNLOAD_TIMEOUT_SECONDS = 3600;

    public static final int LS_TIMEOUT = 120;

    private GcpPathHelper() {}

    // ========== Path utilities ==========

    /**
     * Returns {@code true} if the given path string represents a GCS URI.
     */
    public static boolean isGcpPath(@Nullable final String path) {
        return path != null && path.startsWith(GCS_PREFIX);
    }

    /**
     * Converts a block number to the canonical 36-digit zero-padded file name with {@code .blk.gz} extension.
     * Mirrors {@code FileBlockItemWriter.longToFileName()}.
     *
     * @param blockNumber the block number (non-negative)
     * @return file name, e.g. {@code "000000000000000000000000000000382065.blk.gz"}
     */
    @NonNull
    public static String blockFileName(final long blockNumber) {
        return String.format("%0" + BLOCK_NUMBER_DIGITS + "d", blockNumber) + BLOCK_FILE_EXTENSION;
    }

    /**
     * Builds a full GCS URI for a block file given the base directory and block number.
     */
    @NonNull
    public static String blockFileUri(@NonNull final String gcpBaseDir, final long blockNumber) {
        final String base = gcpBaseDir.endsWith("/") ? gcpBaseDir : gcpBaseDir + "/";
        return base + blockFileName(blockNumber);
    }

    /**
     * Extracts the last path element from a local path or GCS URI, stripping any trailing slash.
     * <p>Example: {@code gs://bucket/prefix/4971437} → {@code 4971437},
     * {@code /local/path/4971437/} → {@code 4971437}.
     */
    @NonNull
    public static String extractLastPathElement(@NonNull final String path) {
        final String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    /**
     * Extracts the second-to-last path element, or {@code "default"} if the path has fewer
     * than two elements after the scheme. Used for node names in GCS paths.
     * <p>Example: {@code gs://bucket/previewnet-node00/4994905} → {@code previewnet-node00}
     */
    @NonNull
    public static String extractSecondToLastPathElement(@NonNull final String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        final int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "default";
        }
        final String parent = trimmed.substring(0, lastSlash);
        final int secondSlash = parent.lastIndexOf('/');
        final String element = parent.substring(secondSlash + 1);
        return element.isEmpty() ? "default" : element;
    }

    // ========== Availability check ==========

    /**
     * Verifies that {@code gcloud} is installed and accessible.
     *
     * @throws IllegalStateException if {@code gcloud} is not found or returns a non-zero exit code
     */
    public static void ensureGcloudAvailable() {
        try {
            final ProcessBuilder pb = new ProcessBuilder(GCLOUD, "--version");
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            // Drain output to avoid blocking
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (reader.readLine() != null) {
                    // discard
                }
            }
            final boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                throw new IllegalStateException("gcloud CLI is not properly configured (exit code: "
                        + (finished ? p.exitValue() : "timeout") + ")");
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(
                    "gcloud CLI is not installed or not on PATH. "
                            + "Install it from https://cloud.google.com/sdk/docs/install",
                    e);
        }
    }

    // ========== Single-file operations ==========

    /**
     * Checks whether a single object exists in GCS without downloading it.
     * Retries on transient failures.
     *
     * @param gcpPath        full GCS URI of the object
     * @param billingProject optional billing project for requester-pays buckets
     * @return {@code true} if the object exists
     */
    public static boolean fileExists(@NonNull final String gcpPath, @Nullable final String billingProject) {
        final List<String> cmd = buildLsCommand(gcpPath, billingProject);
        return executeWithRetry(cmd, SINGLE_FILE_TIMEOUT_SECONDS, false) == 0;
    }

    /**
     * Checks whether a single object exists in GCS, <b>without retrying</b>.
     * Preferred for scatter-gather probing where many "not found" results are expected.
     *
     * @param gcpPath        full GCS URI of the object
     * @param billingProject optional billing project for requester-pays buckets
     * @return {@code true} if the object exists
     */
    public static boolean fileExistsNoRetry(@NonNull final String gcpPath, @Nullable final String billingProject) {
        final List<String> cmd = buildLsCommand(gcpPath, billingProject);
        return executeProcess(cmd, SINGLE_FILE_TIMEOUT_SECONDS, false) == 0;
    }

    /**
     * Downloads a single file from GCS to a local directory.
     *
     * @param gcpPath        full GCS URI of the object
     * @param localDir       local directory where the file will be saved
     * @param billingProject optional billing project
     * @return {@code true} if the download succeeded
     */
    public static boolean downloadFile(
            @NonNull final String gcpPath, @NonNull final Path localDir, @Nullable final String billingProject) {
        try {
            Files.createDirectories(localDir);
        } catch (IOException e) {
            log.error("Failed to create local directory {}", localDir, e);
            return false;
        }
        final List<String> cmd = buildCommand("cp", gcpPath, localDir.toString() + "/", billingProject);
        return executeWithRetry(cmd, SINGLE_FILE_TIMEOUT_SECONDS, true) == 0;
    }

    // ========== Directory download ==========

    /**
     * Recursively downloads a GCS directory to a local directory.
     *
     * @param gcpPath        GCS URI of the directory (e.g. {@code gs://bucket/prefix/})
     * @param localDir       local target directory
     * @param billingProject optional billing project
     * @throws IOException if the download fails
     */
    public static void downloadDirectory(
            @NonNull final String gcpPath, @NonNull final Path localDir, @Nullable final String billingProject)
            throws IOException {
        Files.createDirectories(localDir);
        final String source = gcpPath.endsWith("/") ? gcpPath : gcpPath + "/";
        final List<String> cmd = buildRecursiveCpCommand(source, localDir.toString() + "/", billingProject);
        log.info("Downloading state directory from {} to {} ...", gcpPath, localDir);

        // Count remote objects first to enable progress reporting
        final int totalObjects = countRemoteObjects(source, billingProject);
        if (totalObjects > 0) {
            log.info(CONSOLE, "Downloading state directory from {} ({} objects) ...", gcpPath, totalObjects);
        } else {
            log.info(CONSOLE, "Downloading state directory from {} ...", gcpPath);
        }

        final long start = System.currentTimeMillis();

        // Start progress monitor if we know the total
        final Thread progressMonitor = totalObjects > 0
                ? startProgressMonitor("gcp-state-download-progress", totalObjects, () -> countFilesRecursive(localDir))
                : null;

        final int exitCode = executeWithRetry(cmd, DIRECTORY_DOWNLOAD_TIMEOUT_SECONDS, true);

        // Stop the progress monitor
        if (progressMonitor != null) {
            progressMonitor.interrupt();
            try {
                progressMonitor.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (exitCode != 0) {
            throw new IOException("Failed to download directory from " + gcpPath + " (exit code: " + exitCode + ")");
        }
        final long elapsed = (System.currentTimeMillis() - start) / 1000;
        log.info("State directory download complete in {} seconds: {} -> {}", elapsed, gcpPath, localDir);
        log.info(CONSOLE, "State directory download complete in {} seconds.", elapsed);
    }

    // ========== Bulk file download ==========

    /**
     * Downloads a list of files from a GCS directory to a local directory using batched parallelism.
     *
     * <p>Files are split into {@code parallelism} equal-sized chunks. Each chunk is downloaded
     * by a separate {@code gcloud storage cp} process, where each process receives up to 500 URIs
     * as arguments (matching the proven pattern from the download_blockstream.sh script).
     *
     * @param gcpBaseDir     base GCS directory URI (without trailing file name)
     * @param fileNames      list of file names to download
     * @param localDir       local target directory
     * @param billingProject optional billing project
     * @param parallelism    number of parallel download processes
     * @throws IOException if the download fails or file validation detects problems
     */
    public static void downloadFiles(
            @NonNull final String gcpBaseDir,
            @NonNull final List<String> fileNames,
            @NonNull final Path localDir,
            @Nullable final String billingProject,
            final int parallelism)
            throws IOException {
        if (fileNames.isEmpty()) {
            return;
        }
        Files.createDirectories(localDir);
        final String base = gcpBaseDir.endsWith("/") ? gcpBaseDir : gcpBaseDir + "/";
        final int totalFiles = fileNames.size();
        log.info(
                "Downloading {} block files from {} to {} using {} parallel workers ...",
                totalFiles,
                gcpBaseDir,
                localDir,
                parallelism);

        // Build full GCS URIs
        final List<String> uris = fileNames.stream().map(name -> base + name).collect(Collectors.toList());

        // Write URI list to a temp manifest file
        final Path manifestFile = Files.createTempFile("gcp-download-manifest-", ".txt");
        Files.write(manifestFile, uris);

        // Create wrapper script that receives GCS URIs as positional arguments and appends
        // the local destination directory — needed because xargs inserts args before
        // the end of the command, but gcloud storage cp expects the destination last.
        final Path wrapperScript = Files.createTempFile("gcp-download-", ".sh");
        Files.writeString(wrapperScript, buildDownloadScript(localDir, billingProject));
        wrapperScript.toFile().setExecutable(true);

        final long start = System.currentTimeMillis();
        log.info(CONSOLE, "Downloading {} block files from {} ...", totalFiles, gcpBaseDir);
        try {
            final List<String> cmd = new ArrayList<>();
            cmd.add("xargs");
            cmd.add("-n");
            cmd.add("500");
            cmd.add("-P");
            cmd.add(String.valueOf(Math.max(1, parallelism)));
            cmd.add(wrapperScript.toString());

            // Start a progress monitor that periodically reports to stdout
            final Thread progressMonitor = startProgressMonitor(
                    "gcp-download-progress", totalFiles, () -> countFilesInDir(localDir, BLOCK_FILE_EXTENSION));

            final int exitCode = executeProcess(cmd, DIRECTORY_DOWNLOAD_TIMEOUT_SECONDS, true, manifestFile);

            // Stop the progress monitor
            progressMonitor.interrupt();
            try {
                progressMonitor.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            final long elapsed = (System.currentTimeMillis() - start) / 1000;

            // Always check for missing files regardless of exit code — xargs can return 0
            // even when individual gcloud processes silently fail on some files.
            final int maxRetryPasses = 3;
            for (int pass = 1; pass <= maxRetryPasses; pass++) {
                final List<String> missing = findMissingFiles(fileNames, localDir);
                if (missing.isEmpty()) {
                    break;
                }
                log.info(CONSOLE, "Retry pass {}: {} missing files...", pass, missing.size());
                log.warn("Retry pass {}/{}: {} files missing after download", pass, maxRetryPasses, missing.size());
                retryMissingFilesBatched(base, missing, localDir, billingProject, parallelism);
            }

            // Final validation: check for empty files
            validateDownloadedFiles(localDir, BLOCK_FILE_EXTENSION);

            final long finalCount = countFilesInDir(localDir, BLOCK_FILE_EXTENSION);
            log.info(CONSOLE, "Block file download complete: {} files in {} seconds.", finalCount, elapsed);

        } finally {
            Files.deleteIfExists(manifestFile);
            Files.deleteIfExists(wrapperScript);
        }
    }

    // ========== Progress tracking ==========

    /** Interval between progress reports (in milliseconds). */
    private static final long PROGRESS_INTERVAL_MS = 5_000;

    /** A counting strategy that may throw {@link IOException}. */
    @FunctionalInterface
    private interface FileCounter {
        long count() throws IOException;
    }

    /**
     * Starts a daemon thread that periodically invokes the given counter and prints
     * progress to stdout. The thread runs until interrupted.
     *
     * @param threadName  name for the monitoring thread
     * @param totalFiles  the expected total file count
     * @param counter     strategy for counting completed files
     * @return the monitoring thread (already started)
     */
    private static Thread startProgressMonitor(
            @NonNull final String threadName, final int totalFiles, @NonNull final FileCounter counter) {
        final Thread monitor = new Thread(
                () -> {
                    int lastReportedPercent = -1;
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(PROGRESS_INTERVAL_MS);
                            final long count = counter.count();
                            final int percent = (int) (count * 100 / totalFiles);
                            if (percent / 10 > lastReportedPercent / 10 || count % 5000 == 0) {
                                log.info(CONSOLE, "  {}% ({}/{})", percent, count, totalFiles);
                                lastReportedPercent = percent;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (IOException e) {
                            log.debug("Progress monitor failed to count files", e);
                        }
                    }
                },
                threadName);
        monitor.setDaemon(true);
        monitor.start();
        return monitor;
    }

    /**
     * Counts files with the given extension in the directory (non-recursive).
     */
    static long countFilesInDir(@NonNull final Path dir, @NonNull final String extension) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(extension))
                    .count();
        }
    }

    /**
     * Counts all regular files recursively under the given directory.
     */
    static long countFilesRecursive(@NonNull final Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    /**
     * Counts the number of objects in a GCS path using {@code gcloud storage ls --recursive}.
     * Returns 0 if the count cannot be determined (non-fatal).
     */
    private static int countRemoteObjects(@NonNull final String gcpPath, @Nullable final String billingProject) {
        try {
            final List<String> cmd = new ArrayList<>();
            cmd.add(GCLOUD);
            cmd.add("storage");
            cmd.add("ls");
            cmd.add("--recursive");
            if (billingProject != null && !billingProject.isEmpty()) {
                cmd.add("--billing-project=" + billingProject);
            }
            cmd.add(gcpPath);

            final ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            final Process process = pb.start();
            int count = 0;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Skip directory markers (lines ending with /) and empty lines
                    if (!line.isEmpty() && !line.endsWith("/")) {
                        count++;
                    }
                }
            }
            final boolean finished = process.waitFor(LS_TIMEOUT, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return 0;
            }
            log.debug("Remote object count for {}: {}", gcpPath, count);
            return count;
        } catch (IOException | InterruptedException e) {
            log.debug("Failed to count remote objects for {}", gcpPath, e);
            return 0;
        }
    }

    /**
     * Lists the first object in a GCS directory.
     *
     * @return the full GCS URI of the first object, or {@code null} if the directory is empty
     */
    public static String listFirstFile(@NonNull final String gcpPath, @Nullable final String billingProject) {
        return listFile(gcpPath, billingProject, true);
    }

    /**
     * Lists the last object in a GCS directory. This reads the entire listing (GCS returns
     * lexicographic order; the zero-padded block filenames make that equivalent to numeric order).
     *
     * @return the full GCS URI of the last object, or {@code null} if the directory is empty
     */
    public static String listLastFile(@NonNull final String gcpPath, @Nullable final String billingProject) {
        return listFile(gcpPath, billingProject, false);
    }

    /**
     * Lists either the first or last non-directory object in a GCS directory.
     * When {@code first} is true, the process is killed as soon as the first match is found
     * (avoids streaming the entire listing for large directories).
     */
    private static String listFile(
            @NonNull final String gcpPath, @Nullable final String billingProject, final boolean first) {
        try {
            final List<String> cmd = buildLsCommand(gcpPath, billingProject);
            final ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            final Process process = pb.start();
            String result = null;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty() && !line.endsWith("/")) {
                        result = line.trim();
                        if (first) {
                            process.destroyForcibly();
                            return result;
                        }
                    }
                }
            }
            process.waitFor(LS_TIMEOUT, TimeUnit.SECONDS);
            return result;
        } catch (IOException | InterruptedException e) {
            log.debug("Failed to list {} file in {}", first ? "first" : "last", gcpPath, e);
            return null;
        }
    }

    /**
     * Validates that all downloaded files with the given extension are non-empty.
     *
     * @throws IOException if any file is empty (likely a partial/failed download)
     */
    static void validateDownloadedFiles(@NonNull final Path dir, @NonNull final String extension) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            final List<Path> emptyFiles = stream.filter(
                            p -> p.getFileName().toString().endsWith(extension))
                    .filter(p -> {
                        try {
                            return Files.size(p) == 0;
                        } catch (IOException e) {
                            return true;
                        }
                    })
                    .toList();
            if (!emptyFiles.isEmpty()) {
                throw new IOException(
                        "Found " + emptyFiles.size() + " empty block files after download, indicating partial "
                                + "or failed downloads. First: " + emptyFiles.getFirst());
            }
        }
    }

    // ========== Internal helpers ==========

    /**
     * Returns the list of file names from the expected set that are missing or empty in the local directory.
     */
    private static List<String> findMissingFiles(
            @NonNull final List<String> expectedFileNames, @NonNull final Path localDir) {
        final List<String> missing = new ArrayList<>();
        for (final String name : expectedFileNames) {
            final Path localFile = localDir.resolve(name);
            if (!Files.exists(localFile) || fileIsEmpty(localFile)) {
                missing.add(name);
            }
        }
        return missing;
    }

    /**
     * Retries downloading missing files using the same batched xargs approach as the initial download.
     */
    private static void retryMissingFilesBatched(
            @NonNull final String gcpBase,
            @NonNull final List<String> missingFileNames,
            @NonNull final Path localDir,
            @Nullable final String billingProject,
            final int parallelism) {
        if (missingFileNames.isEmpty()) {
            return;
        }
        try {
            final List<String> uris =
                    missingFileNames.stream().map(name -> gcpBase + name).collect(Collectors.toList());
            final Path manifestFile = Files.createTempFile("gcp-retry-manifest-", ".txt");
            final Path wrapperScript = Files.createTempFile("gcp-retry-", ".sh");
            try {
                Files.write(manifestFile, uris);
                Files.writeString(wrapperScript, buildDownloadScript(localDir, billingProject));
                wrapperScript.toFile().setExecutable(true);

                final List<String> cmd = new ArrayList<>();
                cmd.add("xargs");
                cmd.add("-n");
                cmd.add("500");
                cmd.add("-P");
                cmd.add(String.valueOf(Math.max(1, parallelism)));
                cmd.add(wrapperScript.toString());

                executeProcess(cmd, DIRECTORY_DOWNLOAD_TIMEOUT_SECONDS, true, manifestFile);
            } finally {
                Files.deleteIfExists(manifestFile);
                Files.deleteIfExists(wrapperScript);
            }
        } catch (IOException e) {
            log.error("Retry batch download failed", e);
        }
    }

    private static boolean fileIsEmpty(@NonNull final Path path) {
        try {
            return Files.size(path) == 0;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Builds a small shell script that receives GCS URIs as positional arguments
     * and downloads them to the target directory. This is needed because xargs appends
     * arguments to the end of the command, but {@code gcloud storage cp} expects the
     * destination as the last argument.
     */
    @NonNull
    private static String buildDownloadScript(@NonNull final Path localDir, @Nullable final String billingProject) {
        final StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/sh\n");
        sb.append(GCLOUD);
        sb.append(" storage cp");
        if (billingProject != null && !billingProject.isEmpty()) {
            sb.append(" '--billing-project=").append(billingProject).append("'");
        }
        sb.append(" \"$@\" '").append(localDir.toAbsolutePath()).append("/'\n");
        return sb.toString();
    }

    /**
     * Builds a {@code gcloud storage ls} command for checking object existence.
     */
    @NonNull
    private static List<String> buildLsCommand(@NonNull final String gcpPath, @Nullable final String billingProject) {
        final List<String> cmd = new ArrayList<>();
        cmd.add(GCLOUD);
        cmd.add("storage");
        cmd.add("ls");
        if (billingProject != null && !billingProject.isEmpty()) {
            cmd.add("--billing-project=" + billingProject);
        }
        cmd.add(gcpPath);
        return cmd;
    }

    /**
     * Builds a {@code gcloud storage cp} command with source, destination, and optional billing project.
     */
    @NonNull
    private static List<String> buildCommand(
            @NonNull final String subCommand,
            @NonNull final String source,
            @NonNull final String destination,
            @Nullable final String billingProject) {
        final List<String> cmd = new ArrayList<>();
        cmd.add(GCLOUD);
        cmd.add("storage");
        cmd.add(subCommand);
        if (billingProject != null && !billingProject.isEmpty()) {
            cmd.add("--billing-project=" + billingProject);
        }
        cmd.add(source);
        cmd.add(destination);
        return cmd;
    }

    /**
     * Builds a {@code gcloud storage cp --recursive} command.
     */
    @NonNull
    private static List<String> buildRecursiveCpCommand(
            @NonNull final String source, @NonNull final String destination, @Nullable final String billingProject) {
        final List<String> cmd = new ArrayList<>();
        cmd.add(GCLOUD);
        cmd.add("storage");
        cmd.add("cp");
        cmd.add("--recursive");
        if (billingProject != null && !billingProject.isEmpty()) {
            cmd.add("--billing-project=" + billingProject);
        }
        cmd.add(source);
        cmd.add(destination);
        return cmd;
    }

    /**
     * Executes a command with retry logic for transient failures.
     *
     * @return the exit code of the last attempt
     */
    private static int executeWithRetry(
            @NonNull final List<String> cmd, final long timeoutSeconds, final boolean logOutput) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            final int exitCode = executeProcess(cmd, timeoutSeconds, logOutput);
            if (exitCode == 0) {
                return 0;
            }
            if (attempt < MAX_RETRIES) {
                final long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1)); // exponential backoff
                log.debug("Command failed (attempt {}/{}), retrying in {} ms: {}", attempt, MAX_RETRIES, delay, cmd);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return exitCode;
                }
            }
        }
        return -1; // all retries exhausted
    }

    /**
     * Executes an external process and returns its exit code.
     */
    private static int executeProcess(
            @NonNull final List<String> cmd, final long timeoutSeconds, final boolean logOutput) {
        return executeProcess(cmd, timeoutSeconds, logOutput, null);
    }

    /**
     * Executes an external process and returns its exit code, optionally redirecting stdin
     * from a file (used for portable xargs invocations instead of GNU-only {@code -a}).
     *
     * <p>Output is drained in a separate daemon thread so that {@code waitFor(timeout)} can
     * enforce the timeout even if the process keeps stdout open.
     */
    private static int executeProcess(
            @NonNull final List<String> cmd,
            final long timeoutSeconds,
            final boolean logOutput,
            @Nullable final Path stdinFile) {
        try {
            log.debug("Executing: {}", String.join(" ", cmd));
            final ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (stdinFile != null) {
                pb.redirectInput(stdinFile.toFile());
            }
            final Process process = pb.start();

            // Drain output in a daemon thread so waitFor(timeout) is not blocked
            final Thread drainThread = new Thread(
                    () -> {
                        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (logOutput) {
                                    log.debug("[gcloud] {}", line);
                                }
                            }
                        } catch (IOException e) {
                            // Process was likely destroyed — expected on timeout
                        }
                    },
                    "process-output-drain");
            drainThread.setDaemon(true);
            drainThread.start();

            final boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                // Kill all descendant processes first (gcloud, wrapper scripts spawned by xargs).
                // Without this, destroyForcibly() only kills xargs itself, leaving orphaned gcloud
                // processes that continue writing into the cache directory after Java has returned.
                process.descendants().forEach(descendant -> {
                    log.debug(
                            "Killing descendant process: pid={}, cmd={}",
                            descendant.pid(),
                            descendant.info().commandLine().orElse("unknown"));
                    descendant.destroyForcibly();
                });
                process.destroyForcibly();
                log.error("Process timed out after {} seconds: {}", timeoutSeconds, String.join(" ", cmd));
                return -1;
            }

            // Wait briefly for the drain thread to finish reading any remaining output
            drainThread.join(2000);

            return process.exitValue();
        } catch (IOException | InterruptedException e) {
            log.error("Failed to execute process: {}", String.join(" ", cmd), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }
}
