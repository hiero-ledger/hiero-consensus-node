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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A test container for running a block node server instance.
 */
public class BlockNodeContainer extends GenericContainer<BlockNodeContainer> {
    private static final String BLOCK_NODE_VERSION = "0.37.1-rc1";
    private static final DockerImageName DEFAULT_IMAGE_NAME =
            DockerImageName.parse("ghcr.io/hiero-ledger/hiero-block-node:" + BLOCK_NODE_VERSION);
    private static final int GRPC_PORT = 40840;
    private static final String MAVEN_CENTRAL_BASE_URL = "https://repo1.maven.org/maven2";
    private static final String HIER0_BLOCK_NODE_GROUP_PATH = "org/hiero/block-node";
    private static final String STATE_DIR_IN_CONTAINER = "/opt/hiero/block-node/application-state";
    private static final String RSA_BOOTSTRAP_FILE_NAME = "rsa-bootstrap-roster.json";
    private static final Object PLUGINS_LOCK = new Object();
    private static final List<String> REQUIRED_PLUGIN_ARTIFACTS = List.of(
            "facility-messaging",
            "health",
            "verification",
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
            // Transitive deps of the verification plugin
            Map.entry(
                    "hedera-cryptography-wraps-3.8.1.jar",
                    MAVEN_CENTRAL_BASE_URL
                            + "/com/hedera/cryptography/hedera-cryptography-wraps/3.8.1/hedera-cryptography-wraps-3.8.1.jar"),
            Map.entry(
                    "hedera-cryptography-hints-3.8.1.jar",
                    MAVEN_CENTRAL_BASE_URL
                            + "/com/hedera/cryptography/hedera-cryptography-hints/3.8.1/hedera-cryptography-hints-3.8.1.jar"),
            Map.entry(
                    "hedera-common-nativesupport-3.8.1.jar",
                    MAVEN_CENTRAL_BASE_URL
                            + "/com/hedera/common/hedera-common-nativesupport/3.8.1/hedera-common-nativesupport-3.8.1.jar"),
            Map.entry(
                    "antlr4-runtime-4.13.2.jar",
                    MAVEN_CENTRAL_BASE_URL + "/org/antlr/antlr4-runtime/4.13.2/antlr4-runtime-4.13.2.jar"));
    private String containerId;

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
            DockerImageName dockerImageName, final long blockNodeId, final int port, final String rsaBootstrapJson) {
        super(dockerImageName);

        final Path pluginsDir = ensurePluginsAvailable();
        this.withFileSystemBind(pluginsDir.toString(), pluginsDirInContainer(), BindMode.READ_ONLY);

        if (rsaBootstrapJson != null) {
            final Path stateDir = prepareStateDir(rsaBootstrapJson);
            this.withFileSystemBind(stateDir.toString(), STATE_DIR_IN_CONTAINER, BindMode.READ_WRITE);
        }

        // Expose the gRPC port for block node communication
        this.addFixedExposedPort(port, GRPC_PORT);
        this.withNetworkAliases("block-node-" + blockNodeId)
                .withEnv("VERSION", BLOCK_NODE_VERSION)
                // The health endpoint is served on the same HTTP/2 port as gRPC (40840), which is
                // incompatible with testcontainers' HTTP/1.1 wait strategy. Use a log-message check
                // instead; BlockNodeNetwork.awaitGrpcReadiness() provides the gRPC-level confirmation.
                .waitingFor(Wait.forLogMessage(".*Started BlockNode Server.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
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
    private static Path prepareStateDir(final String rsaBootstrapJson) {
        synchronized (PLUGINS_LOCK) {
            final Path scopeRoot = WorkingDirUtils.workingDirFor(0, null).getParent();
            final Path stateDir;
            if (scopeRoot == null) {
                stateDir = Path.of("build", "block-node", BLOCK_NODE_VERSION, "node")
                        .toAbsolutePath()
                        .normalize();
            } else {
                stateDir = scopeRoot
                        .resolve("block-node")
                        .resolve(BLOCK_NODE_VERSION)
                        .resolve("node")
                        .toAbsolutePath()
                        .normalize();
            }
            try {
                Files.createDirectories(stateDir);
                Files.writeString(stateDir.resolve(RSA_BOOTSTRAP_FILE_NAME), rsaBootstrapJson);
            } catch (final IOException e) {
                throw new RuntimeException("Failed to write RSA bootstrap file to " + stateDir, e);
            }
            // Make the bind-mounted state dir writable by the non-root block node container user so it
            // can persist runtime state (e.g. tss-parameters.bin) and reload it across restarts.
            try {
                Files.setPosixFilePermissions(stateDir, PosixFilePermissions.fromString("rwxrwxrwx"));
            } catch (final UnsupportedOperationException | IOException ignored) {
                // Non-POSIX host; the Docker-based block node tests only run on POSIX filesystems.
            }
            return stateDir;
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
    }

    @Override
    public void stop() {
        if (isRunning()) {
            super.stop();
        }
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
