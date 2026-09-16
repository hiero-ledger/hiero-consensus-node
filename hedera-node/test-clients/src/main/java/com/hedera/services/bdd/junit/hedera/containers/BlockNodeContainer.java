// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.containers;

import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * A test container for running a block node server instance.
 */
public class BlockNodeContainer extends GenericContainer<BlockNodeContainer> {
    private static final Logger logger = LogManager.getLogger(BlockNodeContainer.class);
    private static final String BLOCK_NODE_VERSION = "0.43.0-rc1";
    private static final DockerImageName DEFAULT_IMAGE_NAME =
            DockerImageName.parse("ghcr.io/hiero-ledger/hiero-block-node:" + BLOCK_NODE_VERSION);
    private static final int GRPC_PORT = 40840;
    private static final String MAVEN_CENTRAL_BASE_URL = "https://repo1.maven.org/maven2";
    private static final String HIER0_BLOCK_NODE_GROUP_PATH = "org/hiero/block-node";
    private static final String STATE_DIR_IN_CONTAINER = "/opt/hiero/block-node/application-state";
    private static final String RSA_BOOTSTRAP_FILE_NAME = "rsa-bootstrap-roster.json";
    private static final String LOGGING_CONFIG_IN_CONTAINER = "/opt/hiero/block-node/logs/config/logging.properties";
    private static final String BLOCK_PIPELINE_LOG_LEVEL = "FINE";
    private static final String JAVA_TOOL_OPTIONS_VALUE = "-Djava.util.logging.config.file="
            + LOGGING_CONFIG_IN_CONTAINER + " -Xlog:gc,gc+init,gc+cpu:stderr:time,uptime,level,tags"
            // The block node container is a sibling started through the Docker daemon, so it is not in
            // this job's cgroup; without this its JVM sizes the virtual-thread carrier pool, GC workers
            // and gRPC pools for the whole host. Give it the CPU count the job itself was given.
            + " -XX:ActiveProcessorCount=" + Runtime.getRuntime().availableProcessors();
    /** How often the block node JVM is sampled for a thread dump while its container is running. */
    private static final Duration THREAD_DUMP_INTERVAL = Duration.ofSeconds(30);
    /**
     * Dumps every thread in the block node JVM. {@code Thread.print} adds lock ownership and Java-level
     * deadlock detection; {@code Thread.dump_to_file} is the only form that lists virtual threads.
     * SIGQUIT is the fallback for a JRE image without {@code jcmd}; that dump goes to the JVM's own
     * stdout and so lands in the container log rather than the dump file.
     */
    private static final String THREAD_DUMP_COMMAND = """
            PID=$(grep -l '^java$' /proc/[0-9]*/comm 2>/dev/null | head -1 | cut -d/ -f3)
            [ -z "$PID" ] && PID=1
            F=/tmp/bn-threads.txt
            if command -v jcmd >/dev/null 2>&1; then
              jcmd "$PID" Thread.print -l 2>&1
              jcmd "$PID" Thread.dump_to_file -overwrite -format=plain "$F" >/dev/null 2>&1 && cat "$F"
            else
              kill -3 "$PID"
              echo "jcmd unavailable; sent SIGQUIT to $PID -- the dump is in the container log"
            fi
            """;

    private static final Object PLUGINS_LOCK = new Object();
    private static final List<String> REQUIRED_PLUGIN_ARTIFACTS = List.of(
            "facility-messaging",
            "health",
            "block-verification",
            "blocks-file-recent",
            "blocks-file-historic",
            "block-access-service",
            "server-status",
            "stream-publisher",
            "stream-subscriber",
            "roster-bootstrap-rsa",
            "roster-bootstrap-tss");
    private static final Map<String, String> REQUIRED_EXTRA_JARS = Map.ofEntries(
            Map.entry(
                    "spotbugs-annotations-4.9.8.jar",
                    MAVEN_CENTRAL_BASE_URL
                            + "/com/github/spotbugs/spotbugs-annotations/4.9.8/spotbugs-annotations-4.9.8.jar"),
            Map.entry("disruptor-4.0.0.jar", MAVEN_CENTRAL_BASE_URL + "/com/lmax/disruptor/4.0.0/disruptor-4.0.0.jar"),
            // Transitive deps of the block-verification plugin
            Map.entry(
                    "hedera-cryptography-wraps-3.13.0.jar",
                    MAVEN_CENTRAL_BASE_URL
                            + "/com/hedera/cryptography/hedera-cryptography-wraps/3.13.0/hedera-cryptography-wraps-3.13.0.jar"),
            Map.entry(
                    "hedera-cryptography-hints-3.13.0.jar",
                    MAVEN_CENTRAL_BASE_URL
                            + "/com/hedera/cryptography/hedera-cryptography-hints/3.13.0/hedera-cryptography-hints-3.13.0.jar"),
            Map.entry(
                    "hedera-common-nativesupport-3.13.0.jar",
                    MAVEN_CENTRAL_BASE_URL
                            + "/com/hedera/common/hedera-common-nativesupport/3.13.0/hedera-common-nativesupport-3.13.0.jar"),
            Map.entry(
                    "antlr4-runtime-4.13.2.jar",
                    MAVEN_CENTRAL_BASE_URL + "/org/antlr/antlr4-runtime/4.13.2/antlr4-runtime-4.13.2.jar"));
    private final long blockNodeId;
    private String containerId;
    private ScheduledExecutorService threadDumpSampler;

    /**
     * Creates a new block node container with the default image.
     *
     * @param blockNodeId the id of the block node
     * @param port the internal port of the block node container to expose
     * @param rsaBootstrapJson JSON content for the RSA bootstrap roster file, or {@code null} to skip
     */
    public BlockNodeContainer(final long blockNodeId, final int port, final String rsaBootstrapJson) {
        this(DEFAULT_IMAGE_NAME, blockNodeId, port, rsaBootstrapJson);
    }

    /**
     * Creates a new block node container with the specified image.
     *
     * @param dockerImageName the docker image to use
     * @param rsaBootstrapJson JSON content for the RSA bootstrap roster file, or {@code null} to skip
     */
    private BlockNodeContainer(
            final DockerImageName dockerImageName,
            final long blockNodeId,
            final int port,
            final String rsaBootstrapJson) {
        super(dockerImageName);
        this.blockNodeId = blockNodeId;

        final Path pluginsDir = ensurePluginsAvailable();
        this.withFileSystemBind(pluginsDir.toString(), pluginsDirInContainer(), BindMode.READ_ONLY);

        if (rsaBootstrapJson != null) {
            final Path stateDir = prepareStateDir(blockNodeId, rsaBootstrapJson);
            this.withFileSystemBind(stateDir.toString(), STATE_DIR_IN_CONTAINER, BindMode.READ_WRITE);
        }

        // Expose the gRPC port for block node communication
        this.addFixedExposedPort(port, GRPC_PORT);
        // Diagnostic: make the block pipeline observable in the container log (see loggingProperties()).
        this.withCopyToContainer(Transferable.of(loggingProperties()), LOGGING_CONFIG_IN_CONTAINER);

        this.withNetworkAliases("block-node-" + blockNodeId)
                .withEnv("VERSION", BLOCK_NODE_VERSION)
                .withEnv("JAVA_TOOL_OPTIONS", JAVA_TOOL_OPTIONS_VALUE)
                // The health endpoint is served on the same HTTP/2 port as gRPC (40840), which is
                // incompatible with testcontainers' HTTP/1.1 wait strategy. Use a log-message check
                // instead; BlockNodeNetwork.awaitGrpcReadiness() provides the gRPC-level confirmation.
                .waitingFor(Wait.forLogMessage(".*Started BlockNode Server.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    /**
     * The block node ships every package at INFO, at which a block is received, verified, persisted and
     * acknowledged without emitting a single line -- so an acknowledgement stall leaves no trace at all.
     * This mirrors the bundled config (block-node/app/src/main/resources/logging.properties) and raises
     * the three packages that bracket the pipeline:
     *
     * <ul>
     *   <li>{@code stream.publisher} - "Completed blocks N" (block fully received), handler add/remove,
     *       and the SKIP/RESEND/Behind decisions that show which publisher was elected.</li>
     *   <li>{@code blocks.files.recent} - "Persistence Handle verification started for block N" and
     *       "Wrote verified block N to file", which bracket verification completing.</li>
     *   <li>{@code block.verification} - little below INFO today, but kept so any DEBUG added upstream
     *       is captured.</li>
     * </ul>
     *
     * <p>The per-acknowledgement latency logs in the publisher are TRACE, so FINE deliberately leaves
     * them off. Setting {@code java.util.logging.config.file} makes BlockNodeApp skip its
     * {@code CleanColorfulFormatter.makeLoggingColorful()} call, so the formatter is named explicitly
     * here to keep the log format identical to a default run.
     */
    private static String loggingProperties() {
        return """
                .level=INFO
                org.hiero.block.level=INFO
                org.hiero.block.node.block.verification.level=%1$s
                org.hiero.block.node.stream.publisher.level=%1$s
                org.hiero.block.node.blocks.files.recent.level=%1$s
                io.grpc.level=INFO
                io.helidon.level=INFO
                com.sun.jmx.interceptor.level=INFO
                javax.management.level=INFO
                com.sun.net.httpserver.level=WARNING
                com.sun.net.httpserver.ServerImpl.level=WARNING
                com.sun.net.httpserver.ExchangeImpl.level=WARNING
                handlers=java.util.logging.ConsoleHandler
                java.util.logging.ConsoleHandler.level=ALL
                java.util.logging.ConsoleHandler.formatter=org.hiero.block.node.app.logging.CleanColorfulFormatter
                """.formatted(BLOCK_PIPELINE_LOG_LEVEL);
    }

    private static String pluginsDirInContainer() {
        return "/opt/hiero/block-node/app-" + BLOCK_NODE_VERSION + "/plugins";
    }

    /**
     * The 0.28.0 block node image is "barebone" and requires plugins to be mounted at runtime.
     * This method downloads the required plugin jars (and a small set of extra runtime jars)
     * from Maven Central into a shared temp directory and returns that directory.
     */
    private static Path ensurePluginsAvailable() {
        final Path pluginsDir = pluginCacheDir();
        final Path marker = pluginsDir.resolve(".complete");
        synchronized (PLUGINS_LOCK) {
            try {
                Files.createDirectories(pluginsDir);
                final HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();

                for (final String artifact : REQUIRED_PLUGIN_ARTIFACTS) {
                    final String fileName = artifact + "-" + BLOCK_NODE_VERSION + ".jar";
                    final String url = MAVEN_CENTRAL_BASE_URL + "/" + HIER0_BLOCK_NODE_GROUP_PATH + "/" + artifact + "/"
                            + BLOCK_NODE_VERSION + "/" + fileName;
                    downloadIfMissing(client, url, pluginsDir.resolve(fileName));
                }
                for (final Map.Entry<String, String> entry : REQUIRED_EXTRA_JARS.entrySet()) {
                    downloadIfMissing(client, entry.getValue(), pluginsDir.resolve(entry.getKey()));
                }
                Files.writeString(marker, "ok\n");
                return pluginsDir;
            } catch (final IOException e) {
                throw new RuntimeException("Failed to prepare block node plugins in " + pluginsDir, e);
            }
        }
    }

    /**
     * Returns a stable on-disk plugin cache directory under the same build root used by the test-clients
     * subprocess/embedded networks (i.e., next to {@code node0}, {@code node1}, ... working directories).
     */
    private static Path pluginCacheDir() {
        final Path scopeRoot = WorkingDirUtils.workingDirFor(0, null).getParent();
        if (scopeRoot == null) {
            // workingDirFor() always includes node0, so this should never happen; keep a safe fallback
            return Path.of("build", "block-node", BLOCK_NODE_VERSION, "plugins")
                    .toAbsolutePath()
                    .normalize();
        }
        return scopeRoot
                .resolve("block-node")
                .resolve(BLOCK_NODE_VERSION)
                .resolve("plugins")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Writes the RSA bootstrap JSON to the block node state directory and returns the directory path.
     * The file is written as {@value RSA_BOOTSTRAP_FILE_NAME} so the block node app picks it up
     * from its default {@code app.state.rsaBootstrapFilePath} without any config override.
     */
    private static Path prepareStateDir(final long blockNodeId, final String rsaBootstrapJson) {
        synchronized (PLUGINS_LOCK) {
            final Path scopeRoot = WorkingDirUtils.workingDirFor(0, null).getParent();
            final Path stateDir = getStateDir(blockNodeId, scopeRoot);
            try {
                Files.createDirectories(stateDir);
                // Clear only this node's own dir (never a running peer's); also drops a prior
                // test's stale tss-parameters.bin and dodges AccessDenied from the non-root writer.
                deleteDirectoryContents(stateDir);
                Files.writeString(stateDir.resolve(RSA_BOOTSTRAP_FILE_NAME), rsaBootstrapJson);
            } catch (final IOException e) {
                throw new RuntimeException("Failed to write RSA bootstrap file to " + stateDir, e);
            }
            // Let the block node's non-root user persist runtime state (e.g. tss-parameters.bin) so
            // it survives an in-test container restart; no-op on non-POSIX hosts.
            try {
                Files.setPosixFilePermissions(stateDir, PosixFilePermissions.fromString("rwxrwxrwx"));
            } catch (final UnsupportedOperationException | IOException ignored) {
                // Docker-based block node tests only run on POSIX filesystems
            }
            return stateDir;
        }
    }

    private static @NonNull Path getStateDir(final long blockNodeId, final Path scopeRoot) {
        final String nodeDir = "node-" + blockNodeId;
        final Path stateDir;
        if (scopeRoot == null) {
            stateDir = Path.of("build", "block-node", BLOCK_NODE_VERSION, nodeDir)
                    .toAbsolutePath()
                    .normalize();
        } else {
            stateDir = scopeRoot
                    .resolve("block-node")
                    .resolve(BLOCK_NODE_VERSION)
                    .resolve(nodeDir)
                    .toAbsolutePath()
                    .normalize();
        }
        return stateDir;
    }

    /**
     * Best-effort recursive removal of a directory's contents (the directory itself is kept). The
     * block node runs as a non-root user and may leave files this process cannot overwrite; since
     * we own the directory we can still unlink them. Per-entry failures are ignored so a leftover
     * entry never aborts container setup.
     */
    private static void deleteDirectoryContents(final Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.newDirectoryStream(dir)) {
            for (final Path entry : stream) {
                try {
                    if (Files.isDirectory(entry)) {
                        deleteDirectoryContents(entry);
                    }
                    Files.deleteIfExists(entry);
                } catch (final IOException ignored) {
                    // best-effort per entry
                }
            }
        } catch (final IOException | DirectoryIteratorException ignored) {
            // best-effort: could not list or iterate the directory (DirectoryIteratorException
            // wraps an IOException thrown while advancing the stream) — never abort setup
        }
    }

    private static void downloadIfMissing(final HttpClient client, final String url, final Path destination)
            throws IOException {
        if (Files.exists(destination) && Files.size(destination) > 0) {
            return;
        }
        final Path tmp = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);

        final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        final HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Failed downloading " + url + " (HTTP " + response.statusCode() + ")");
        }
        try (InputStream in = response.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void start() {
        if (!isRunning()) {
            super.start();
        }
        containerId = getContainerId();
        startThreadDumpSampler();
    }

    @Override
    public void stop() {
        stopThreadDumpSampler();
        if (isRunning()) {
            super.stop();
        }
    }

    /**
     * Diagnostic: samples a full thread dump from the block node JVM every {@link #THREAD_DUMP_INTERVAL}.
     * The block node has been seen to stop verifying blocks permanently and silently -- the publisher
     * keeps accepting them in ~100us while nothing is ever verified or acknowledged again -- which
     * saturates the consensus node block buffer and parks its handle thread. A time series of dumps,
     * healthy ones first, is what identifies which thread stopped and what it is waiting on.
     */
    private void startThreadDumpSampler() {
        if (threadDumpSampler != null) {
            return;
        }
        threadDumpSampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "bn-thread-dump-" + blockNodeId);
            thread.setDaemon(true);
            return thread;
        });
        final long periodSeconds = THREAD_DUMP_INTERVAL.toSeconds();
        threadDumpSampler.scheduleWithFixedDelay(
                this::captureThreadDump, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    private void stopThreadDumpSampler() {
        if (threadDumpSampler != null) {
            threadDumpSampler.shutdownNow();
            threadDumpSampler = null;
        }
    }

    private void captureThreadDump() {
        if (!isRunning()) {
            return;
        }
        try {
            final ExecResult result = execInContainer("sh", "-c", THREAD_DUMP_COMMAND);
            final String body = result.getStdout().isBlank()
                    ? "no stdout; exit=" + result.getExitCode() + " stderr=" + result.getStderr()
                    : result.getStdout();
            final Path dumpFile = threadDumpFile();
            Files.createDirectories(dumpFile.getParent());
            Files.writeString(
                    dumpFile,
                    "===== block node " + blockNodeId + " @ " + Instant.now() + " =====\n" + body + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final Exception e) {
            // Best-effort diagnostic; a test must never fail because a dump could not be taken
            logger.warn("Failed to capture a thread dump for block node {}", blockNodeId, e);
        }
    }

    /**
     * Dumps land beside the {@code block-node-<id>.log} written by
     * {@code BlockNodeNetwork.dumpContainerLogs()}, so the CI artifact glob already uploads them.
     */
    private Path threadDumpFile() {
        final Path scopeRoot = WorkingDirUtils.workingDirFor(0, null).getParent();
        final Path outputDir = (scopeRoot == null
                        ? Path.of("build", "hapi-test").toAbsolutePath()
                        : scopeRoot)
                .resolve("block-node-containers")
                .resolve("output");
        return outputDir.resolve("block-node-" + blockNodeId + "-threads.log");
    }

    /**
     * Gets the mapped port for the block node gRPC server.
     *
     * @return the host port mapped to the container's internal port
     */
    public int getPort() {
        return getMappedPort(GRPC_PORT);
    }

    /**
     * Pauses the container, freezing all processes inside it.
     * The container will remain in memory but will not consume CPU resources.
     */
    public void pause() {
        if (!isRunning()) {
            throw new IllegalStateException("Cannot pause container that is not running");
        }

        try (StopContainerCmd stopContainerCmd = getDockerClient().stopContainerCmd(containerId)) {
            stopContainerCmd.exec();
        } catch (Exception e) {
            throw new RuntimeException("Failed to pause container: " + containerId, e);
        }
    }

    /**
     * Resumes the container, resuming all processes inside it.
     */
    public void resume() {
        try (StartContainerCmd startContainerCmd = getDockerClient().startContainerCmd(containerId)) {
            startContainerCmd.exec();

            // Wait a moment for the container to fully resume
            try {
                Thread.sleep(1000); // 1-second warm-up period
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to resume container: " + containerId, e);
        }
    }

    @Override
    public String toString() {
        return this.getHost() + ":" + this.getPort();
    }
}
