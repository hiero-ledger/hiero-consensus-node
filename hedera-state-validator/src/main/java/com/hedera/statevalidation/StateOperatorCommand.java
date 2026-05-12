// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.gcp.GcpPathHelper;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "operator",
        mixinStandardHelpOptions = true,
        subcommands = {
            ValidateCommand.class,
            AnalyzeCommand.class,
            IntrospectCommand.class,
            ExportCommand.class,
            SortedExportCommand.class,
            DiffCommand.class,
            CompactionCommand.class,
            ApplyBlocksCommand.class
        },
        description = "CLI tool with validation and introspection modes.")
public class StateOperatorCommand implements Runnable {

    private static final Logger log = LogManager.getLogger(StateOperatorCommand.class);

    @Parameters(index = "0", description = "State directory. Accepts a local path or a GCS URI (gs://...).")
    private String stateDir;

    @Option(
            names = {"--cleanup-temp"},
            defaultValue = "false",
            description = "Delete temporary directories created for GCP downloads after execution.")
    private boolean cleanupTemp;

    /**
     * Tracks all temporary directories created during this execution, so they can optionally be
     * cleaned up on exit.
     */
    private final List<Path> tempDirectories = new ArrayList<>();

    /**
     * The resolved local state directory. Set after {@link #resolveAndGetStateDir()} is called.
     * For local paths, this is the original path. For GCS paths, this is the temp directory where
     * the state was downloaded.
     */
    private File resolvedStateDir;

    /**
     * Resolves the state directory. If the path is a GCS URI ({@code gs://...}), the directory
     * is downloaded to a temporary local directory first. The resolved local path is then set
     * as the {@code state.dir} system property for downstream consumers.
     *
     * <p>This method must be called by subcommands <b>before</b> accessing the state.
     *
     * @throws RuntimeException if GCP download fails or gcloud is not available
     */
    void resolveAndGetStateDir() {
        if (resolvedStateDir != null) {
            // Already resolved (idempotent)
            System.setProperty("state.dir", resolvedStateDir.getAbsolutePath());
            return;
        }

        if (GcpPathHelper.isGcpPath(stateDir)) {
            GcpPathHelper.ensureGcloudAvailable();
            try {
                // Use a deterministic directory name derived from the GCS path so that
                // repeated runs reuse an already-downloaded state instead of re-downloading.
                // The directory is placed in the current working directory (not system /tmp)
                // to stay on the same filesystem — the validator creates hard links internally.
                final Path cacheDir = Path.of(".", gcpPathToCacheDirName(stateDir));

                // gcloud storage cp --recursive preserves the last path component of the source,
                // e.g. gs://bucket/prefix/4994905 → <cacheDir>/4994905/
                final String lastComponent = GcpPathHelper.extractLastPathElement(stateDir);
                final Path innerDir = cacheDir.resolve(lastComponent);

                if (Files.isDirectory(innerDir) && hasContent(innerDir)) {
                    // Reuse previously downloaded state
                    resolvedStateDir = innerDir.toFile();
                    trackTempDirectory(cacheDir);
                    log.info("Reusing cached state directory: {}", resolvedStateDir.getAbsolutePath());
                    System.out.printf("Reusing cached state directory: %s%n", resolvedStateDir.getAbsolutePath());
                } else {
                    Files.createDirectories(cacheDir);
                    trackTempDirectory(cacheDir);
                    log.info("State directory is a GCS path. Downloading {} to {} ...", stateDir, cacheDir);
                    GcpPathHelper.downloadDirectory(stateDir, cacheDir, null);

                    if (Files.isDirectory(innerDir)) {
                        resolvedStateDir = innerDir.toFile();
                    } else {
                        // Fallback in case the directory structure is different than expected
                        resolvedStateDir = cacheDir.toFile();
                    }
                }
                log.info("State directory resolved to: {}", resolvedStateDir.getAbsolutePath());
            } catch (IOException e) {
                throw new RuntimeException("Failed to download state directory from GCS: " + stateDir, e);
            }
        } else {
            resolvedStateDir = new File(stateDir);
            if (!resolvedStateDir.exists()) {
                throw new RuntimeException("State directory does not exist: " + stateDir);
            }
        }

        System.setProperty("state.dir", resolvedStateDir.getAbsolutePath());
    }

    /**
     * @deprecated Use {@link #resolveAndGetStateDir()} instead. Kept for backward compatibility
     * during the transition period.
     */
    @Deprecated
    void initializeStateDir() {
        resolveAndGetStateDir();
    }

    /**
     * Returns the resolved state directory. Must be called after {@link #resolveAndGetStateDir()}.
     *
     * @throws IllegalStateException if called before resolution
     */
    File getStateDir() {
        if (resolvedStateDir == null) {
            throw new IllegalStateException("State directory not yet resolved. Call resolveAndGetStateDir() first.");
        }
        return resolvedStateDir;
    }

    /**
     * Returns the raw state directory string as provided on the command line.
     * May be a local path or a {@code gs://} URI.
     */
    String getRawStateDir() {
        return stateDir;
    }

    /**
     * Registers a temporary directory for tracking and optional cleanup.
     */
    void trackTempDirectory(@NonNull final Path tempDir) {
        tempDirectories.add(tempDir);
        log.info("Temporary directory created: {}", tempDir);
    }

    /**
     * Returns whether temp cleanup is enabled.
     */
    boolean isCleanupTemp() {
        return cleanupTemp;
    }

    @Override
    public void run() {
        // This runs if no subcommand is provided
        System.out.println(
                "Specify a subcommand (validate/analyze/introspect/export/sorted-export/compact/apply-blocks).");
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        try {
            final StateOperatorCommand rootCommand = new StateOperatorCommand();
            int exitCode = new CommandLine(rootCommand).execute(args);
            log.info("Execution time: {} ms.", System.currentTimeMillis() - startTime);

            // Cleanup temp directories if requested
            if (rootCommand.cleanupTemp && !rootCommand.tempDirectories.isEmpty()) {
                for (final Path tempDir : rootCommand.tempDirectories) {
                    try {
                        deleteRecursively(tempDir);
                        log.info("Cleaned up temporary directory: {}", tempDir);
                    } catch (IOException e) {
                        log.warn("Failed to clean up temporary directory: {}", tempDir, e);
                    }
                }
            }

            System.exit(exitCode);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Derives a deterministic cache directory name from a GCS path.
     * The last path element is always a round number.
     * <p>Example: {@code gs://preview-testnet-backup/previewnet-node00/4994905}
     * → {@code state-validator-cache-4994905}
     */
    static String gcpPathToCacheDirName(@NonNull final String gcpPath) {
        return "state-validator-cache-" + GcpPathHelper.extractLastPathElement(gcpPath);
    }

    /**
     * Returns {@code true} if the directory exists and contains at least one regular file
     * (recursively). A quick check to confirm a previous download actually produced content.
     */
    private static boolean hasContent(@NonNull final Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Recursively deletes a directory and all its contents.
     */
    private static void deleteRecursively(@NonNull final Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path child : entries.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.delete(path);
    }
}
