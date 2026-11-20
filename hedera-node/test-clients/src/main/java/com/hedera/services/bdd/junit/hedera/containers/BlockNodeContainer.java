// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.containers;

import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * A test container for running a block node server instance.
 */
public class BlockNodeContainer extends GenericContainer<BlockNodeContainer> {
    private static final String BLOCK_NODE_VERSION_PROPERTY = "hapi.spec.blocknode.version";
    private static final String BLOCK_NODE_VERSION = requiredBlockNodeVersion();
    private static final DockerImageName DEFAULT_IMAGE_NAME =
            DockerImageName.parse("ghcr.io/hiero-ledger/hiero-block-node:" + BLOCK_NODE_VERSION);
    private static final int GRPC_PORT = 40840;
    private static final String MAVEN_CENTRAL_BASE_URL = "https://repo1.maven.org/maven2";
    private static final String BLOCK_NODE_GROUP = "org.hiero.block-node";
    private static final String STATE_DIR_IN_CONTAINER = "/opt/hiero/block-node/application-state";
    private static final String RSA_BOOTSTRAP_FILE_NAME = "rsa-bootstrap-roster.json";
    private static final String LOGGING_CONFIG_IN_CONTAINER = "/opt/hiero/block-node/logs/config/logging.properties";
    private static final String BLOCK_PIPELINE_LOG_LEVEL = "FINE";
    private static final String JAVA_TOOL_OPTIONS_VALUE = "-Djava.util.logging.config.file="
            + LOGGING_CONFIG_IN_CONTAINER + " -Xlog:gc,gc+init,gc+cpu:stderr:time,uptime,level,tags"
            // The container is a Docker-daemon sibling, outside this job's cgroup, so without this its
            // JVM sizes thread pools and heap for the whole host rather than the CPUs this job has.
            + " -XX:ActiveProcessorCount=" + Runtime.getRuntime().availableProcessors();
    private static final Object PLUGINS_LOCK = new Object();
    // The app logs one of these once its main thread finishes startup, or dies (say, for a missing plugin)
    private static final String STARTED_LINE = "Started BlockNode Server";
    private static final String DIED_LINE = "Exception in thread \"main\"";
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
    // Published, and then needed, only by some versions (facility-app-state from 0.45.0-rc2 on)
    private static final List<String> OPTIONAL_PLUGIN_ARTIFACTS = List.of("facility-app-state");
    // Jars the plugins need that the image lacks, at the versions declared by the POMs read before them (the
    // plugins', then these jars' own), so the crypto libraries follow the block node version
    private static final List<String> DECLARED_EXTRA_JARS = List.of(
            "com.github.spotbugs:spotbugs-annotations",
            "com.lmax:disruptor",
            "com.hedera.cryptography:hedera-cryptography-hints",
            "com.hedera.cryptography:hedera-cryptography-wraps:verifier",
            "com.hedera.common:hedera-common-nativesupport");
    // Needed too, but no POM read here declares it
    private static final String ANTLR_RUNTIME = "org.antlr:antlr4-runtime";
    private static final String ANTLR_RUNTIME_VERSION = "4.13.2";
    // Guarded by PLUGINS_LOCK
    private static @Nullable Path preparedPluginsDir;
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
            final DockerImageName dockerImageName,
            final long blockNodeId,
            final int port,
            final String rsaBootstrapJson) {
        super(dockerImageName);

        final Path pluginsDir = ensurePluginsAvailable();
        this.withFileSystemBind(pluginsDir.toString(), pluginsDirInContainer(), BindMode.READ_ONLY);

        if (rsaBootstrapJson != null) {
            final Path stateDir = prepareStateDir(blockNodeId, rsaBootstrapJson);
            this.withFileSystemBind(stateDir.toString(), STATE_DIR_IN_CONTAINER, BindMode.READ_WRITE);
        }

        // Expose the gRPC port for block node communication
        this.addFixedExposedPort(port, GRPC_PORT);
        // Raise block node log levels so the streaming pipeline is visible in the container log.
        this.withCopyToContainer(Transferable.of(loggingProperties()), LOGGING_CONFIG_IN_CONTAINER);

        this.withNetworkAliases("block-node-" + blockNodeId)
                .withEnv("VERSION", BLOCK_NODE_VERSION)
                .withEnv("JAVA_TOOL_OPTIONS", JAVA_TOOL_OPTIONS_VALUE)
                // The health endpoint is served on the same HTTP/2 port as gRPC (40840), which is
                // incompatible with testcontainers' HTTP/1.1 wait strategy. Use a log-message check
                // instead; BlockNodeNetwork.awaitGrpcReadiness() provides the gRPC-level confirmation.
                // A dead main thread leaves the container running, so stop waiting then too (see start())
                .waitingFor(Wait.forLogMessage(".*(" + STARTED_LINE + "|" + DIED_LINE + ").*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    /**
     * The block node logs nothing per-block at INFO, so a stalled acknowledgement leaves no trace at
     * all. FINE on these packages reports each block received, verified and persisted. The formatter
     * must be named here: setting {@code java.util.logging.config.file} makes BlockNodeApp skip its
     * own {@code CleanColorfulFormatter} wiring.
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

    /**
     * The version CITR deploys, which the test-clients build reads from {@code block-node-version} in
     * {@code .github/workflows/support/citr/.citr-env}; deliberately no default, so it can't drift from that.
     */
    private static String requiredBlockNodeVersion() {
        final String version = System.getProperty(BLOCK_NODE_VERSION_PROPERTY, "");
        if (version.isBlank()) {
            throw new IllegalStateException("No block node version; run through Gradle, which sets "
                    + BLOCK_NODE_VERSION_PROPERTY + " from .github/workflows/support/citr/.citr-env");
        }
        return version;
    }

    private static String pluginsDirInContainer() {
        return "/opt/hiero/block-node/app-" + BLOCK_NODE_VERSION + "/plugins";
    }

    /**
     * The block node image is "barebone" and requires plugins to be mounted at runtime. Downloads the plugins and the
     * extra jars they need from Maven Central into the plugin cache directory and returns it. The extra jars' versions
     * come from the block node's own POMs, so a new {@code block-node-version} in .citr-env needs no change here
     * unless it needs another plugin.
     */
    private static Path ensurePluginsAvailable() {
        final Path pluginsDir = pluginCacheDir();
        synchronized (PLUGINS_LOCK) {
            if (pluginsDir.equals(preparedPluginsDir)) {
                return pluginsDir;
            }
            try {
                Files.createDirectories(pluginsDir);
                final HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                final Map<String, String> declaredVersions = new HashMap<>();
                final Set<String> jars = new HashSet<>();
                for (final String plugin : REQUIRED_PLUGIN_ARTIFACTS) {
                    fetch(
                            client,
                            pluginsDir,
                            BLOCK_NODE_GROUP + ":" + plugin,
                            BLOCK_NODE_VERSION,
                            true,
                            declaredVersions,
                            jars);
                }
                for (final String plugin : OPTIONAL_PLUGIN_ARTIFACTS) {
                    fetch(
                            client,
                            pluginsDir,
                            BLOCK_NODE_GROUP + ":" + plugin,
                            BLOCK_NODE_VERSION,
                            false,
                            declaredVersions,
                            jars);
                }
                for (final String coordinates : DECLARED_EXTRA_JARS) {
                    final String version = declaredVersions.get(coordinates);
                    if (version == null) {
                        throw new IOException("none of its POMs declares " + coordinates);
                    }
                    fetch(client, pluginsDir, coordinates, version, true, declaredVersions, jars);
                }
                fetch(client, pluginsDir, ANTLR_RUNTIME, ANTLR_RUNTIME_VERSION, true, declaredVersions, jars);
                // Another version's jars would be duplicate modules
                deleteJarsOtherThan(pluginsDir, jars);
                Files.writeString(pluginsDir.resolve(".complete"), "ok\n");
                preparedPluginsDir = pluginsDir;
                return pluginsDir;
            } catch (final IOException e) {
                throw new RuntimeException(
                        "Failed to prepare plugins for Block Node " + BLOCK_NODE_VERSION + " in " + pluginsDir
                                + " (its plugin jars must be published on Maven Central, and the plugin list and extra"
                                + " jars here must match what that version needs): " + e.getMessage(),
                        e);
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

    /**
     * Downloads the jar at {@code group:artifact[:classifier]} and the given version, after adding the versions its
     * POM declares to {@code declaredVersions}. Does nothing if an optional artifact isn't published.
     */
    private static void fetch(
            final HttpClient client,
            final Path dir,
            final String coordinates,
            final String version,
            final boolean required,
            final Map<String, String> declaredVersions,
            final Set<String> jars)
            throws IOException {
        final String[] parts = coordinates.split(":");
        final String base = MAVEN_CENTRAL_BASE_URL + "/" + parts[0].replace('.', '/') + "/" + parts[1] + "/" + version
                + "/" + parts[1] + "-" + version;
        final String pom = getText(client, base + ".pom", required);
        if (pom == null) {
            return;
        }
        declaredVersions(pom).forEach(declaredVersions::putIfAbsent);
        final String suffix = (parts.length > 2 ? "-" + parts[2] : "") + ".jar";
        final String jar = parts[1] + "-" + version + suffix;
        downloadIfMissing(client, base + suffix, dir.resolve(jar));
        jars.add(jar);
    }

    // Returns null for an optional resource that isn't published
    private static @Nullable String getText(final HttpClient client, final String url, final boolean required)
            throws IOException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        final HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e);
        }
        if (response.statusCode() == 404 && !required) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("Failed downloading " + url + " (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    // group:artifact[:classifier] to version, for each dependency the POM declares with a version
    private static Map<String, String> declaredVersions(final String pom) throws IOException {
        try {
            final var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(pom)));
            final Map<String, String> versions = new HashMap<>();
            final NodeList dependencies = document.getElementsByTagName("dependency");
            for (int i = 0; i < dependencies.getLength(); i++) {
                final var dependency = (Element) dependencies.item(i);
                final String version = childText(dependency, "version");
                if (version != null) {
                    final String classifier = childText(dependency, "classifier");
                    versions.put(
                            childText(dependency, "groupId") + ":" + childText(dependency, "artifactId")
                                    + (classifier == null ? "" : ":" + classifier),
                            version);
                }
            }
            return versions;
        } catch (final ParserConfigurationException | SAXException e) {
            throw new IOException("Unreadable POM", e);
        }
    }

    private static @Nullable String childText(final Element element, final String tag) {
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && tag.equals(child.getTagName())) {
                return child.getTextContent().strip();
            }
        }
        return null;
    }

    private static void deleteJarsOtherThan(final Path dir, final Set<String> jars) throws IOException {
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (final Path jar : stream) {
                if (!jars.contains(jar.getFileName().toString())) {
                    Files.delete(jar);
                }
            }
        }
    }

    @Override
    public void start() {
        if (!isRunning()) {
            super.start();
            failIfAppDied();
        }
        containerId = getContainerId();
    }

    // Turns a block node that can't start into a quick, explicit failure rather than a startup timeout
    private void failIfAppDied() {
        final String logs = getLogs();
        final int died = logs.indexOf(DIED_LINE);
        if (died >= 0 && !logs.contains(STARTED_LINE)) {
            stop();
            throw new IllegalStateException("Block node " + BLOCK_NODE_VERSION + " failed to start: "
                    + logs.substring(died).lines().findFirst().orElse(DIED_LINE));
        }
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
