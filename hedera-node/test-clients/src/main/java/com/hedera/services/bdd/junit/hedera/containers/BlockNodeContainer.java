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
    private static final String BLOCK_NODE_VERSION = "0.28.0";
    private static final DockerImageName DEFAULT_IMAGE_NAME =
            DockerImageName.parse("ghcr.io/hiero-ledger/hiero-block-node:" + BLOCK_NODE_VERSION);
    private static final int GRPC_PORT = 40840;
    private static final int HEALTH_PORT = 16007;
    private static final String MAVEN_CENTRAL_BASE_URL = "https://repo1.maven.org/maven2";
    private static final String HIER0_BLOCK_NODE_GROUP_PATH = "org/hiero/block-node";
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
            "stream-subscriber");
    private static final Map<String, String> REQUIRED_EXTRA_JARS = Map.of(
            "spotbugs-annotations-4.9.8.jar",
            MAVEN_CENTRAL_BASE_URL + "/com/github/spotbugs/spotbugs-annotations/4.9.8/spotbugs-annotations-4.9.8.jar",
            "disruptor-4.0.0.jar",
            MAVEN_CENTRAL_BASE_URL + "/com/lmax/disruptor/4.0.0/disruptor-4.0.0.jar");
    private String containerId;

    /**
     * Creates a new block node container with the default image.
     * @param blockNodeId the id of the block node
     * @param port the internal port of the block node container to expose
     */
    public BlockNodeContainer(final long blockNodeId, final int port) {
        this(DEFAULT_IMAGE_NAME, blockNodeId, port);
    }

    /**
     * Creates a new block node container with the specified image.
     *
     * @param dockerImageName the docker image to use
     */
    private BlockNodeContainer(DockerImageName dockerImageName, final long blockNodeId, final int port) {
        super(dockerImageName);

        final Path pluginsDir = ensurePluginsAvailable();
        this.withFileSystemBind(pluginsDir.toString(), pluginsDirInContainer(), BindMode.READ_ONLY);

        // Expose the gRPC port for block node communication
        this.addFixedExposedPort(port, GRPC_PORT);
        // Also expose the health check port
        this.addExposedPort(HEALTH_PORT);
        this.withNetworkAliases("block-node-" + blockNodeId)
                .withEnv("VERSION", BLOCK_NODE_VERSION)
                // Use HTTP health check on the health port to verify the service is ready
                .waitingFor(Wait.forHttp("/-/healthy").forPort(HEALTH_PORT).withStartupTimeout(Duration.ofMinutes(2)));
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
        final String scope = System.getProperty("hapi.blockNode.plugins.scope");
        final Path scopeRoot = WorkingDirUtils.workingDirFor(0, scope).getParent();
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
        waitForHealthy(Duration.ofMinutes(2));
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
     * Waits for the block node container to be healthy by configuring the health check timeout.
     *
     * @param timeout the maximum duration to wait for the container's health check to pass
     */
    public void waitForHealthy(final Duration timeout) {
        this.waitingFor(Wait.forHealthcheck().withStartupTimeout(timeout));
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
