// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.utils;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.info.DiskStartupNetworks.ARCHIVE;
import static com.hedera.node.app.info.DiskStartupNetworks.GENESIS_NETWORK_JSON;
import static com.hedera.node.app.info.DiskStartupNetworks.OVERRIDE_NETWORK_JSON;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.config.converter.SemanticVersionConverter;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.props.JutilPropertySource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class WorkingDirUtils {
    /**
     * The "classic" admin key used for PR-check networks. This matches the default configured
     * node-admin key in dev/CI environments and is sufficient for tests that require an admin key
     * to be present on {@link Node} metadata.
     */
    private static final Key CLASSIC_ADMIN_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("0aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110e92"))
            .build();

    private static final Path BASE_WORKING_LOC = Path.of("./build");
    private static final String DEFAULT_SCOPE = "hapi";
    private static final String KEYS_FOLDER = "keys";
    private static final String CONFIG_FOLDER = "config";
    private static final String LOG4J2_XML = "log4j2.xml";
    private static final String SETTINGS_TXT = "settings.txt";
    private static final String PROJECT_BOOTSTRAP_ASSETS_LOC = "hedera-node/configuration/dev";
    private static final String TEST_CLIENTS_BOOTSTRAP_ASSETS_LOC = "../configuration/dev";
    private static final String GOSSIP_CERTS_RESOURCE = "hapi-test-gossip-certs.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern NUMERIC_KEY_PATTERN = Pattern.compile("\\d+");
    private static final Pattern LOAD_KEYS_FROM_PFX_PATTERN =
            Pattern.compile("^(\\s*loadKeysFromPfxFiles\\s*,\\s*)(\\S+)(.*)$");
    private static final Map<Long, GossipKeyMaterial> GOSSIP_KEY_MATERIAL =
            new ConcurrentHashMap<>(loadPreGeneratedGossipKeys());
    private static final Map<Long, byte[]> GOSSIP_CERT_CACHE = new ConcurrentHashMap<>();

    static {
        GOSSIP_KEY_MATERIAL.forEach((nodeId, material) -> GOSSIP_CERT_CACHE.put(nodeId, material.sigCertDer()));
    }

    /**
     * Backwards-compatible default certificate for call sites that do not yet pass a node id.
     */
    public static final Bytes VALID_CERT = Bytes.wrap(gossipCaCertificateForNodeId(0L));

    public static final String DATA_DIR = "data";
    public static final String CONFIG_DIR = "config";
    public static final String OUTPUT_DIR = "output";
    public static final String UPGRADE_DIR = "upgrade";
    public static final String CURRENT_DIR = "current";
    public static final String CONFIG_TXT = "config.txt";
    public static final String GENESIS_PROPERTIES = "genesis.properties";
    public static final String ERROR_REDIRECT_FILE = "test-clients.log";
    public static final String STATE_METADATA_FILE = "stateMetadata.txt";
    public static final String NODE_ADMIN_KEYS_JSON = "node-admin-keys.json";
    public static final String CANDIDATE_ROSTER_JSON = "candidate-roster.json";
    public static final String APPLICATION_PROPERTIES = "application.properties";

    private static final List<String> WORKING_DIR_DATA_FOLDERS = List.of(KEYS_FOLDER, CONFIG_FOLDER, UPGRADE_DIR);

    private static final String LOG4J2_DATE_FORMAT = "%d{yyyy-MM-dd HH:mm:ss.SSS}";

    private WorkingDirUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Returns a deterministic, pre-generated gossip certificate for a given node id.
     *
     * @param nodeId the node id
     * @return the DER-encoded certificate bytes
     */
    public static byte[] gossipCaCertificateForNodeId(final long nodeId) {
        final var cert = GOSSIP_CERT_CACHE.get(nodeId);
        if (cert == null) {
            throw new IllegalStateException(missingKeyMaterialMessage(Set.of(nodeId)));
        }
        return Arrays.copyOf(cert, cert.length);
    }

    /**
     * Returns the path to the working directory for the given node ID.
     *
     * @param nodeId the ID of the node
     * @param scope if non-null, an additional scope to use for the working directory
     * @return the path to the working directory
     */
    public static Path workingDirFor(final long nodeId, @Nullable String scope) {
        scope = scope == null ? DEFAULT_SCOPE : scope;
        return BASE_WORKING_LOC
                .resolve(scope + "-test")
                .resolve("node" + nodeId)
                .normalize();
    }

    /**
     * Initializes the working directory by deleting it and creating a new one
     * with the given <i>config.txt</i> file.
     *
     * @param workingDir the path to the working directory
     * @param network genesis network
     * @param nodeId own nodeId
     */
    public static void recreateWorkingDir(
            @NonNull final Path workingDir, @NonNull final Network network, final long nodeId) {
        requireNonNull(workingDir);
        requireNonNull(network);

        // Clean up any existing directory structure
        rm(workingDir);
        // Initialize the data folders
        WORKING_DIR_DATA_FOLDERS.forEach(folder ->
                createDirectoriesUnchecked(workingDir.resolve(DATA_DIR).resolve(folder)));
        // Initialize the current upgrade folder
        createDirectoriesUnchecked(
                workingDir.resolve(DATA_DIR).resolve(UPGRADE_DIR).resolve(CURRENT_DIR));
        // Write genesis network (genesis-network.json) files
        writeStringUnchecked(
                workingDir.resolve(DATA_DIR).resolve(CONFIG_FOLDER).resolve(GENESIS_NETWORK_JSON),
                Network.JSON.toJSON(network));
        // Copy the bootstrap assets into the working directory
        copyBootstrapAssets(bootstrapAssetsLoc(), workingDir);
        // Ensure pregenerated gossip key/cert material is present and selected via settings.txt
        syncPreGeneratedGossipKeys(workingDir, nodeId);
        // Update the log4j2.xml file with the correct output directory
        updateLog4j2XmlOutputDir(workingDir, nodeId);
    }

    public static void recreateWorkingDir(
            @NonNull final Path workingDir, @NonNull final String configTxt, final long nodeId) {
        recreateWorkingDir(workingDir, configTxt, nodeId, Map.of());
    }

    /**
     * Initializes a working directory by writing the supplied {@code config.txt} and derived {@code genesis-network.json}
     * resources; then copying bootstrap assets; then ensuring pre-generated gossip key material is present.
     *
     * <p>This overload exists for callers that still originate from {@code config.txt} format; most newer callers
     * should prefer passing a {@link Network} directly.</p>
     *
     * @param workingDir the working directory to recreate
     * @param configTxt the contents of {@code config.txt}
     * @param nodeId the local node id whose private signing key should be written
     * @param serviceEndpoints optional service endpoint overrides by node id
     */
    public static void recreateWorkingDir(
            @NonNull final Path workingDir,
            @NonNull final String configTxt,
            final long nodeId,
            @NonNull final Map<Long, ServiceEndpoint> serviceEndpoints) {
        requireNonNull(workingDir);
        requireNonNull(configTxt);
        requireNonNull(serviceEndpoints);

        // Clean up any existing directory structure
        rm(workingDir);
        // Initialize the data folders
        WORKING_DIR_DATA_FOLDERS.forEach(folder ->
                createDirectoriesUnchecked(workingDir.resolve(DATA_DIR).resolve(folder)));
        // Initialize the current upgrade folder
        createDirectoriesUnchecked(
                workingDir.resolve(DATA_DIR).resolve(UPGRADE_DIR).resolve(CURRENT_DIR));
        // Write the address book (config.txt) and genesis network (genesis-network.json) files
        writeStringUnchecked(workingDir.resolve(CONFIG_TXT), configTxt);
        final var network = networkFrom(configTxt, OnlyRoster.NO, serviceEndpoints);
        writeStringUnchecked(
                workingDir.resolve(DATA_DIR).resolve(CONFIG_FOLDER).resolve(GENESIS_NETWORK_JSON),
                Network.JSON.toJSON(network));
        // Copy the bootstrap assets into the working directory
        copyBootstrapAssets(bootstrapAssetsLoc(), workingDir);
        // Ensure pregenerated gossip key/cert material is present and selected via settings.txt
        syncPreGeneratedGossipKeys(workingDir, nodeId);
        // Update the log4j2.xml file with the correct output directory
        updateLog4j2XmlOutputDir(workingDir, nodeId);
    }

    /**
     * Updates the <i>upgrade.artifacts.path</i> property in the <i>application.properties</i> file
     *
     * @param propertiesPath the path to the <i>application.properties</i> file
     * @param upgradeArtifactsPath the path to the upgrade artifacts directory
     */
    public static void updateUpgradeArtifactsProperty(
            @NonNull final Path propertiesPath, @NonNull final Path upgradeArtifactsPath) {
        updateBootstrapProperties(
                propertiesPath, Map.of("networkAdmin.upgradeArtifactsPath", upgradeArtifactsPath.toString()));
    }

    /**
     * Updates the given key/value property override at the given location
     *
     * @param propertiesPath the path to the properties file
     * @param overrides the key/value property overrides
     */
    public static void updateBootstrapProperties(
            @NonNull final Path propertiesPath, @NonNull final Map<String, String> overrides) {
        final var properties = new Properties();
        try {
            try (final var in = Files.newInputStream(propertiesPath)) {
                properties.load(in);
            }
            overrides.forEach(properties::setProperty);
            try (final var out = Files.newOutputStream(propertiesPath)) {
                properties.store(out, null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the path to the <i>application.properties</i> file used to bootstrap an embedded or subprocess network.
     *
     * @return the path to the <i>application.properties</i> file
     */
    public static JutilPropertySource hapiTestStartupProperties() {
        return new JutilPropertySource(bootstrapAssetsLoc().resolve(APPLICATION_PROPERTIES));
    }

    /**
     * Returns the version in the project's {@code version.txt} file.
     *
     * @return the version
     */
    public @NonNull static SemanticVersion workingDirVersion() {
        final var loc = Paths.get(System.getProperty("user.dir")).endsWith("hedera-services")
                ? "version.txt"
                : "../../version.txt";
        final var versionLiteral = readStringUnchecked(Paths.get(loc)).trim();
        return requireNonNull(new SemanticVersionConverter().convert(versionLiteral));
    }

    private static Path bootstrapAssetsLoc() {
        return Paths.get(System.getProperty("user.dir")).endsWith("hedera-services")
                ? Path.of(PROJECT_BOOTSTRAP_ASSETS_LOC)
                : Path.of(TEST_CLIENTS_BOOTSTRAP_ASSETS_LOC);
    }

    private static void updateLog4j2XmlOutputDir(@NonNull final Path workingDir, long nodeId) {
        final var path = workingDir.resolve(LOG4J2_XML);
        final var log4j2Xml = readStringUnchecked(path);
        final var updatedLog4j2Xml = log4j2Xml
                .replace("</Appenders>\n" + "  <Loggers>", """
                                  <RollingFile name="TestClientRollingFile" fileName="output/test-clients.log"
                                    filePattern="output/test-clients-%d{yyyy-MM-dd}-%i.log.gz">
                                    <PatternLayout>
                                      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p %-4L %c{1} - %m{nolookups}%n</pattern>
                                    </PatternLayout>
                                    <Policies>
                                      <TimeBasedTriggeringPolicy/>
                                      <SizeBasedTriggeringPolicy size="100 MB"/>
                                    </Policies>
                                    <DefaultRolloverStrategy max="10">
                                      <Delete basePath="output" maxDepth="3">
                                        <IfFileName glob="test-clients-*.log.gz">
                                          <IfLastModified age="P3D"/>
                                        </IfFileName>
                                      </Delete>
                                    </DefaultRolloverStrategy>
                                  </RollingFile>
                                </Appenders>
                                <Loggers>

                                  <Logger name="com.hedera.services.bdd" level="info" additivity="false">
                                    <AppenderRef ref="Console"/>
                                    <AppenderRef ref="TestClientRollingFile"/>
                                  </Logger>
                                 \s""")
                .replace(
                        "output/",
                        workingDir.resolve(OUTPUT_DIR).toAbsolutePath().normalize() + "/")
                // Differentiate between node outputs in combined logging
                .replace(LOG4J2_DATE_FORMAT, LOG4J2_DATE_FORMAT + " &lt;" + "n" + nodeId + "&gt;");
        writeStringUnchecked(path, updatedLog4j2Xml, StandardOpenOption.WRITE);
    }

    /**
     * Recursively deletes the given path.
     *
     * @param path the path to delete
     */
    public static void rm(@NonNull final Path path) {
        if (Files.exists(path)) {
            try (Stream<Path> paths = Files.walk(path)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Returns the given path as a file after a best-effort attempt to ensure it exists.
     *
     * @param path the path to ensure exists
     * @return the path as a file
     */
    public static File guaranteedExtantFile(@NonNull final Path path) {
        if (!Files.exists(path)) {
            try {
                Files.createFile(guaranteedExtantDir(path.getParent()).resolve(path.getName(path.getNameCount() - 1)));
            } catch (IOException ignore) {
                // We don't care if the file already exists
            }
        }
        return path.toFile();
    }

    /**
     * Returns the given path after a best-effort attempt to ensure it exists.
     *
     * @param path the path to ensure exists
     * @return the path
     */
    public static Path guaranteedExtantDir(@NonNull final Path path) {
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                // We don't care if the directory already exists
            }
        }
        return path;
    }

    /**
     * Reads all bytes from a file, throwing an unchecked exception if an {@link IOException} occurs.
     * @param path the path to read
     * @return the bytes at the given path
     */
    public static byte[] readBytesUnchecked(@NonNull final Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readStringUnchecked(@NonNull final Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeStringUnchecked(
            @NonNull final Path path, @NonNull final String content, @NonNull final OpenOption... options) {
        try {
            Files.writeString(path, content, options);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes bytes to a file, throwing an unchecked exception if an {@link IOException} occurs.
     *
     * @param path the path to write
     * @param content the bytes to write
     */
    private static void writeBytesUnchecked(@NonNull final Path path, @NonNull final byte[] content) {
        try {
            Files.write(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeBytesIfMissingOrMatch(
            @NonNull final Path path, @NonNull final byte[] content, @NonNull final String description) {
        if (Files.exists(path)) {
            try {
                if (!Arrays.equals(Files.readAllBytes(path), content)) {
                    throw new IllegalStateException("Mismatched " + description + " at " + path);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }
        writeBytesUnchecked(path, content);
    }

    private static void createDirectoriesUnchecked(@NonNull final Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Ensures pre-generated gossip keys are available for the roster implied by the working directory.
     * Uses candidate-roster first, then override-network, then genesis-network, then config.txt fallback.
     *
     * @param workingDir the working directory
     * @param nodeId the local node id
     */
    public static void syncPreGeneratedGossipKeys(@NonNull final Path workingDir, final long nodeId) {
        requireNonNull(workingDir);
        final var nodeIds = nodeIdsForGossipKeys(workingDir);
        if (!nodeIds.contains(nodeId)) {
            throw new IllegalStateException(
                    "Local node id " + nodeId + " is not present in roster node IDs " + nodeIds + " for " + workingDir);
        }
        final var preGeneratedKeys = preGeneratedGossipKeysForNodeIds(nodeIds);
        writePreGeneratedGossipKeys(workingDir.resolve(DATA_DIR).resolve(KEYS_FOLDER), nodeId, preGeneratedKeys);
        setLoadKeysFromPfxFiles(workingDir, true);
    }

    /**
     * Determines the set of roster node ids a working directory implies for gossip key material.
     *
     * <p>During restarts/upgrades, the "active" roster is generally reflected first in the candidate roster and/or
     * override network; this method checks those before falling back to the genesis network or legacy config.txt.</p>
     *
     * @param workingDir the working directory
     * @return the non-empty set of node ids implied by the working directory
     * @throws IllegalStateException if no roster source can be found or parsed
     */
    private static @NonNull Set<Long> nodeIdsForGossipKeys(@NonNull final Path workingDir) {
        final var candidateRosterNodeIds = nodeIdsFromCandidateRoster(workingDir);
        if (candidateRosterNodeIds != null && !candidateRosterNodeIds.isEmpty()) {
            return candidateRosterNodeIds;
        }
        final var overrideNetworkNodeIds = nodeIdsFromOverrideNetwork(workingDir);
        if (overrideNetworkNodeIds != null && !overrideNetworkNodeIds.isEmpty()) {
            return overrideNetworkNodeIds;
        }
        final var genesisNetworkNodeIds = nodeIdsFromGenesisNetwork(workingDir);
        if (genesisNetworkNodeIds != null && !genesisNetworkNodeIds.isEmpty()) {
            return genesisNetworkNodeIds;
        }
        final var configNodeIds = nodeIdsFromConfig(workingDir);
        if (configNodeIds != null && !configNodeIds.isEmpty()) {
            return configNodeIds;
        }
        throw new IllegalStateException("Unable to determine roster node IDs for " + workingDir);
    }

    /**
     * Attempts to parse roster node ids from {@code candidate-roster.json} in the working directory.
     *
     * @param workingDir the working directory
     * @return the parsed node ids, or {@code null} if the candidate roster does not exist
     */
    private static @Nullable Set<Long> nodeIdsFromCandidateRoster(@NonNull final Path workingDir) {
        final var candidateRosterPath = workingDir.resolve(CANDIDATE_ROSTER_JSON);
        if (!Files.exists(candidateRosterPath)) {
            return null;
        }
        return nodeIdsFromNetworkJson(candidateRosterPath, "candidate roster");
    }

    /**
     * Attempts to parse roster node ids from {@code genesis-network.json} in the working directory.
     *
     * @param workingDir the working directory
     * @return the parsed node ids, or {@code null} if the genesis network does not exist
     */
    private static @Nullable Set<Long> nodeIdsFromGenesisNetwork(@NonNull final Path workingDir) {
        final var configDir = workingDir.resolve(DATA_DIR).resolve(CONFIG_FOLDER);
        final var genesisNetworkPath = configDir.resolve(GENESIS_NETWORK_JSON);
        if (Files.exists(genesisNetworkPath)) {
            return nodeIdsFromNetworkJson(genesisNetworkPath, "genesis network");
        }
        final var archivedGenesisNetworkPath = configDir.resolve(ARCHIVE).resolve(GENESIS_NETWORK_JSON);
        if (Files.exists(archivedGenesisNetworkPath)) {
            return nodeIdsFromNetworkJson(archivedGenesisNetworkPath, "archived genesis network");
        }
        return null;
    }

    /**
     * Attempts to parse roster node ids from the latest {@code override-network.json} available under
     * {@code data/config}, including scoped override directories and the archived config directory.
     *
     * @param workingDir the working directory
     * @return the parsed node ids, or {@code null} if no override network exists
     */
    private static @Nullable Set<Long> nodeIdsFromOverrideNetwork(@NonNull final Path workingDir) {
        final var configDir = workingDir.resolve(DATA_DIR).resolve(CONFIG_FOLDER);
        final var nodeIds = nodeIdsFromOverrideNetworkIn(configDir);
        if (nodeIds != null && !nodeIds.isEmpty()) {
            return nodeIds;
        }
        return nodeIdsFromOverrideNetworkIn(configDir.resolve(ARCHIVE));
    }

    /**
     * Attempts to parse roster node ids from {@code override-network.json} in the given config directory. If the
     * unscoped override file is not present, searches numeric scoped override directories and returns the latest.
     *
     * @param configDir the config directory to inspect
     * @return the parsed node ids, or {@code null} if no override network exists
     */
    private static @Nullable Set<Long> nodeIdsFromOverrideNetworkIn(@NonNull final Path configDir) {
        if (!Files.isDirectory(configDir)) {
            return null;
        }
        final var overrideNetworkPath = configDir.resolve(OVERRIDE_NETWORK_JSON);
        if (Files.exists(overrideNetworkPath)) {
            return nodeIdsFromNetworkJson(overrideNetworkPath, "override network");
        }
        try (final var dirs = Files.list(configDir)) {
            final var latestScopedOverride = dirs.filter(Files::isDirectory)
                    .filter(dir -> NUMERIC_KEY_PATTERN
                            .matcher(dir.getFileName().toString())
                            .matches())
                    .map(dir -> dir.resolve(OVERRIDE_NETWORK_JSON))
                    .filter(Files::exists)
                    .max(Comparator.comparingLong(path ->
                            Long.parseLong(path.getParent().getFileName().toString())));
            return latestScopedOverride
                    .map(path -> nodeIdsFromNetworkJson(path, "scoped override network"))
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Attempts to parse roster node ids by scanning {@code config.txt} (or its archived copy).
     *
     * @param workingDir the working directory
     * @return the parsed node ids, or {@code null} if no config.txt can be found
     */
    private static @Nullable Set<Long> nodeIdsFromConfig(@NonNull final Path workingDir) {
        Path configPath = workingDir.resolve(CONFIG_TXT);
        if (!Files.exists(configPath)) {
            configPath = workingDir.resolve(ARCHIVE).resolve(CONFIG_TXT);
        }
        if (!Files.exists(configPath)) {
            return null;
        }
        final var configTxt = readStringUnchecked(configPath);
        final Set<Long> nodeIds = new TreeSet<>();
        for (final var rawLine : configTxt.split("\n")) {
            final var line = rawLine.trim();
            if (!line.startsWith("address,")) {
                continue;
            }
            final var parts = line.split(",\\s*");
            if (parts.length > 1) {
                nodeIds.add(Long.parseLong(parts[1]));
            }
        }
        return nodeIds;
    }

    /**
     * Parses roster node ids from a {@link Network} JSON file.
     *
     * @param path the file to parse
     * @param kind a human-friendly label used in error messages
     * @return the parsed node ids
     */
    private static Set<Long> nodeIdsFromNetworkJson(@NonNull final Path path, @NonNull final String kind) {
        try (final var fin = Files.newInputStream(path)) {
            final var network = Network.JSON.parse(new ReadableStreamingData(fin));
            final Set<Long> nodeIds = new HashSet<>();
            network.nodeMetadata().stream()
                    .map(NodeMetadata::rosterEntryOrThrow)
                    .forEach(entry -> nodeIds.add(entry.nodeId()));
            return nodeIds;
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Failed to parse " + kind + " at " + path, e);
        }
    }

    /**
     * Writes pre-generated gossip signing keys and certificates into the given keys directory.
     *
     * <p>All public certificates are written. Only the local node's private signing key is written into its working
     * directory.</p>
     *
     * @param keysDir the keys directory for the node
     * @param nodeId the local node id
     * @param keyMaterialByNode the pre-generated key material for all roster nodes
     */
    private static void writePreGeneratedGossipKeys(
            @NonNull final Path keysDir,
            final long nodeId,
            @NonNull final Map<Long, GossipKeyMaterial> keyMaterialByNode) {
        requireNonNull(keysDir);
        requireNonNull(keyMaterialByNode);
        createDirectoriesUnchecked(keysDir);
        for (final var entry : keyMaterialByNode.entrySet()) {
            final var nodeName = "node" + (entry.getKey() + 1);
            final var certPath = keysDir.resolve(String.format("s-public-%s.pem", nodeName));
            writeBytesIfMissingOrMatch(certPath, entry.getValue().sigCertPem(), "public signing certificate");
            if (entry.getKey() == nodeId) {
                final var privateKeyPath = keysDir.resolve(String.format("s-private-%s.pem", nodeName));
                writeBytesIfMissingOrMatch(privateKeyPath, entry.getValue().sigPrivateKeyPem(), "private signing key");
            }
        }
    }

    /**
     * Updates {@code settings.txt} to control whether PEM keys should be loaded from disk.
     *
     * @param workingDir the working directory
     * @param enabled whether to load PEM keys from disk
     */
    private static void setLoadKeysFromPfxFiles(@NonNull final Path workingDir, final boolean enabled) {
        final var settingsPath = workingDir.resolve(SETTINGS_TXT);
        final var settings = readStringUnchecked(settingsPath);
        final var lines = settings.split("\\R", -1);
        boolean found = false;
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            final var matcher = LOAD_KEYS_FROM_PFX_PATTERN.matcher(lines[i]);
            if (matcher.matches()) {
                found = true;
                if (!matcher.group(2).equals(Boolean.toString(enabled))) {
                    lines[i] = matcher.group(1) + enabled + matcher.group(3);
                    changed = true;
                }
                break;
            }
        }
        if (!found) {
            throw new IllegalStateException("Missing loadKeysFromPfxFiles setting at " + settingsPath);
        }
        if (changed) {
            writeStringUnchecked(settingsPath, String.join(System.lineSeparator(), lines));
        }
    }

    private static void copyBootstrapAssets(@NonNull final Path assetDir, @NonNull final Path workingDir) {
        try (final var files = Files.walk(assetDir)) {
            files.filter(file -> !file.equals(assetDir)).forEach(file -> {
                final var fileName = file.getFileName().toString();
                // Skip genesis-network.json as it's already written by recreateWorkingDir()
                if (GENESIS_NETWORK_JSON.equals(fileName)) {
                    return;
                }
                if (fileName.endsWith(".properties") || fileName.endsWith(".json")) {
                    copyUnchecked(
                            file,
                            workingDir
                                    .resolve(DATA_DIR)
                                    .resolve(CONFIG_FOLDER)
                                    .resolve(file.getFileName().toString()));
                } else {
                    copyUnchecked(file, workingDir.resolve(file.getFileName().toString()));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Copy a file from the source path to the target path, creating parent directories as needed
     * and throwing an unchecked exception if an {@link IOException} occurs.
     *
     * @param source the source path
     * @param target the target path
     */
    public static void copyUnchecked(@NonNull final Path source, @NonNull final Path target) {
        copyUnchecked(source, target, new CopyOption[] {});
    }

    /**
     * Copy a file from the source path to the target path, creating parent directories as needed
     * and throwing an unchecked exception if an {@link IOException} occurs.
     *
     * @param source the source path
     * @param target the target path
     * @param options the copy options to use
     */
    public static void copyUnchecked(
            @NonNull final Path source, @NonNull final Path target, @NonNull final CopyOption... options) {
        try {
            final var parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, target, options);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Copy a directory tree from the source path to the target path, replacing files when present.
     *
     * @param source the source directory
     * @param target the target directory
     */
    public static void copyDirectoryUnchecked(@NonNull final Path source, @NonNull final Path target) {
        try (final var paths = Files.walk(source)) {
            paths.forEach(path -> {
                final var relative = source.relativize(path);
                final var destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    createDirectoriesUnchecked(destination);
                } else {
                    copyUnchecked(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Ensure a directory exists at the given path, creating it if necessary.
     *
     * @param path The path to ensure exists as a directory.
     */
    public static void ensureDir(@NonNull final String path) {
        requireNonNull(path);
        final var f = new File(path);
        if (!f.exists() && !f.mkdirs()) {
            throw new IllegalStateException("Failed to create directory: " + f.getAbsolutePath());
        }
    }

    /**
     * Creates a network from the given <i>config.txt</i> file.
     *
     * @param configTxt the contents of the <i>config.txt</i> file
     * @param onlyRoster if true, only the roster entries will be set in the network
     * @param serviceEndpoints optional service endpoint overrides by node id
     * @return the network
     */
    public static Network networkFrom(
            @NonNull final String configTxt,
            @NonNull final OnlyRoster onlyRoster,
            @NonNull final Map<Long, ServiceEndpoint> serviceEndpoints) {
        requireNonNull(configTxt);
        requireNonNull(onlyRoster);
        requireNonNull(serviceEndpoints);

        final var addressLines = Arrays.stream(configTxt.split("\n"))
                .map(String::trim)
                .filter(line -> line.startsWith("address,"))
                .toList();
        final var nodeIds = addressLines.stream()
                .map(line -> line.split(",\\s*"))
                .map(parts -> Long.parseLong(parts[1]))
                .toList();
        final var certsByNodeId = generatedCertsForNodeIds(nodeIds);

        final var nodeMetadata = addressLines.stream()
                .map(line -> {
                    final var parts = line.split(",\\s*");
                    if (parts.length < 10) {
                        throw new IllegalArgumentException("Malformed config line: " + line);
                    }
                    final long nodeId = Long.parseLong(parts[1]);
                    final long weight = Long.parseLong(parts[4]);
                    final var gossipEndpoints =
                            List.of(endpointFrom(parts[5], parts[6]), endpointFrom(parts[7], parts[8]));
                    final var cert = certsByNodeId.get(nodeId);
                    if (cert == null) {
                        throw new IllegalStateException("Missing generated gossip certificate for node " + nodeId);
                    }
                    final var metadata = NodeMetadata.newBuilder()
                            .rosterEntry(new RosterEntry(nodeId, weight, cert, gossipEndpoints));
                    final var nodeAccount = toPbj(HapiPropertySource.asAccount(parts[9]));
                    if (onlyRoster == OnlyRoster.NO) {
                        final ServiceEndpoint serviceEndpoint =
                                serviceEndpoints.getOrDefault(nodeId, endpointFrom(parts[5], parts[6]));
                        metadata.node(new Node(
                                nodeId,
                                nodeAccount,
                                "node" + (nodeId + 1),
                                gossipEndpoints,
                                List.of(serviceEndpoint),
                                cert,
                                // The gRPC certificate hash is irrelevant for PR checks
                                Bytes.EMPTY,
                                weight,
                                false,
                                CLASSIC_ADMIN_KEY,
                                false,
                                null));
                    }
                    return metadata.build();
                })
                .toList();
        return Network.newBuilder().nodeMetadata(nodeMetadata).build();
    }

    public static Network networkFrom(@NonNull final String configTxt, @NonNull final OnlyRoster onlyRoster) {
        return networkFrom(configTxt, onlyRoster, Map.of());
    }

    public static Network networkFrom(
            @NonNull final Network network,
            @NonNull final OnlyRoster onlyRoster,
            @NonNull final Map<Long, ServiceEndpoint> serviceEndpoints) {
        requireNonNull(network);
        requireNonNull(onlyRoster);
        requireNonNull(serviceEndpoints);

        final var updatedMetadata = network.nodeMetadata().stream()
                .map(metadata -> {
                    final var rosterEntry = metadata.rosterEntryOrThrow();
                    final var builder = NodeMetadata.newBuilder().rosterEntry(rosterEntry);
                    if (onlyRoster == OnlyRoster.NO) {
                        final var currentNode = metadata.nodeOrThrow();
                        final var serviceEndpoint = serviceEndpoints.getOrDefault(
                                rosterEntry.nodeId(),
                                currentNode.serviceEndpoint().isEmpty()
                                        ? rosterEntry.gossipEndpoint().getFirst()
                                        : currentNode.serviceEndpoint().getFirst());
                        builder.node(currentNode
                                .copyBuilder()
                                .serviceEndpoint(List.of(serviceEndpoint))
                                .build());
                    }
                    return builder.build();
                })
                .toList();
        return network.copyBuilder().nodeMetadata(updatedMetadata).build();
    }

    public static Network networkFrom(@NonNull final Network network, @NonNull final OnlyRoster onlyRoster) {
        return networkFrom(network, onlyRoster, Map.of());
    }

    /**
     * Returns a map of deterministic gossip certificates for the given node ids.
     *
     * @param nodeIds the node ids
     * @return a mapping from node id to DER-encoded certificate bytes (wrapped as {@link Bytes})
     */
    private static Map<Long, Bytes> generatedCertsForNodeIds(@NonNull final List<Long> nodeIds) {
        final Map<Long, Bytes> certsByNodeId = new HashMap<>();
        final var keyMaterialByNodeId = preGeneratedGossipKeysForNodeIds(new TreeSet<>(nodeIds));
        for (final var nodeId : nodeIds) {
            certsByNodeId.put(nodeId, Bytes.wrap(keyMaterialByNodeId.get(nodeId).sigCertDer()));
        }
        return certsByNodeId;
    }

    /**
     * Builds a {@link ServiceEndpoint} from host and port literals.
     *
     * @param hostLiteral the host
     * @param portLiteral the port
     * @return the endpoint
     */
    private static ServiceEndpoint endpointFrom(@NonNull final String hostLiteral, @NonNull final String portLiteral) {
        return HapiPropertySource.asServiceEndpoint(hostLiteral + ":" + portLiteral);
    }

    /**
     * Returns pre-generated gossip key material for all requested node ids.
     *
     * @param nodeIds the node ids that must be satisfied
     * @return immutable mapping from node id to gossip key material
     * @throws IllegalStateException if any node ids are missing from {@link #GOSSIP_CERTS_RESOURCE}
     */
    private static Map<Long, GossipKeyMaterial> preGeneratedGossipKeysForNodeIds(@NonNull final Set<Long> nodeIds) {
        final var missingNodeIds = missingNodeIds(nodeIds);
        if (!missingNodeIds.isEmpty()) {
            throw new IllegalStateException(missingKeyMaterialMessage(missingNodeIds));
        }
        final Map<Long, GossipKeyMaterial> material = new HashMap<>();
        for (final var nodeId : nodeIds) {
            material.put(nodeId, GOSSIP_KEY_MATERIAL.get(nodeId));
        }
        return Map.copyOf(material);
    }

    /**
     * Returns the subset of requested node ids that are not present in the pre-generated key material store.
     *
     * @param nodeIds the requested ids
     * @return any missing ids
     */
    private static Set<Long> missingNodeIds(@NonNull final Iterable<Long> nodeIds) {
        final Set<Long> missing = new TreeSet<>();
        for (final var nodeId : nodeIds) {
            if (!GOSSIP_KEY_MATERIAL.containsKey(nodeId)) {
                missing.add(nodeId);
            }
        }
        return missing;
    }

    /**
     * Builds a human-friendly error message for missing key material.
     *
     * @param nodeIds the missing node ids
     * @return an error message
     */
    private static String missingKeyMaterialMessage(@NonNull final Set<Long> nodeIds) {
        return "Missing pre-generated gossip key material for node IDs "
                + nodeIds
                + " in "
                + GOSSIP_CERTS_RESOURCE
                + ".";
    }

    /**
     * Loads pre-generated gossip certificates and private keys from {@link #GOSSIP_CERTS_RESOURCE}.
     *
     * <p>The JSON resource is expected to be an object mapping numeric node-id strings to objects containing
     * {@code sigCertPem} and {@code sigPrivateKeyPem} fields, both base64-encoded PEM byte arrays.</p>
     *
     * @return immutable mapping from node id to decoded key material, or empty if the resource is absent
     */
    private static Map<Long, GossipKeyMaterial> loadPreGeneratedGossipKeys() {
        try (InputStream input = WorkingDirUtils.class.getClassLoader().getResourceAsStream(GOSSIP_CERTS_RESOURCE)) {
            if (input == null) {
                return Map.of();
            }
            final var root = OBJECT_MAPPER.readTree(input);
            if (!root.isObject()) {
                throw new IllegalStateException("Invalid gossip key material JSON: expected object");
            }
            final Map<Long, GossipKeyMaterial> decoded = new HashMap<>();
            final var fields = root.fields();
            while (fields.hasNext()) {
                final var entry = fields.next();
                final var key = entry.getKey();
                if (!NUMERIC_KEY_PATTERN.matcher(key).matches()) {
                    continue;
                }
                final var node = entry.getValue();
                if (!node.isObject()) {
                    throw new IllegalStateException("Invalid gossip key material entry for node " + key);
                }
                final var sigCertNode = node.get("sigCertPem");
                final var sigPrivateNode = node.get("sigPrivateKeyPem");
                if (sigCertNode == null || sigPrivateNode == null) {
                    throw new IllegalStateException("Incomplete gossip key material for node " + key);
                }
                final var sigCertPem = Base64.getDecoder().decode(sigCertNode.asText());
                final var sigPrivateKeyPem = Base64.getDecoder().decode(sigPrivateNode.asText());
                final long nodeId = Long.parseLong(key);
                decoded.put(
                        nodeId, new GossipKeyMaterial(sigCertPem, sigPrivateKeyPem, decodeCertificateDer(sigCertPem)));
            }
            return Map.copyOf(decoded);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to load pre-generated gossip key material", e);
        }
    }

    /**
     * Extracts DER bytes from a PEM-encoded certificate.
     *
     * @param pemBytes PEM bytes (ASCII)
     * @return DER bytes
     */
    private static byte[] decodeCertificateDer(@NonNull final byte[] pemBytes) {
        final var pem = new String(pemBytes, StandardCharsets.US_ASCII);
        final var header = "-----BEGIN CERTIFICATE-----";
        final var footer = "-----END CERTIFICATE-----";
        final int start = pem.indexOf(header);
        final int end = pem.indexOf(footer);
        if (start == -1 || end == -1 || end <= start) {
            throw new IllegalArgumentException("Invalid PEM certificate content");
        }
        final var base64Body = pem.substring(start + header.length(), end).replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64Body);
    }

    private static final class GossipKeyMaterial {
        private final byte[] sigCertPem;
        private final byte[] sigPrivateKeyPem;
        private final byte[] sigCertDer;

        private GossipKeyMaterial(
                @NonNull final byte[] sigCertPem,
                @NonNull final byte[] sigPrivateKeyPem,
                @NonNull final byte[] sigCertDer) {
            this.sigCertPem = requireNonNull(sigCertPem);
            this.sigPrivateKeyPem = requireNonNull(sigPrivateKeyPem);
            this.sigCertDer = requireNonNull(sigCertDer);
        }

        private byte[] sigCertPem() {
            return Arrays.copyOf(sigCertPem, sigCertPem.length);
        }

        private byte[] sigPrivateKeyPem() {
            return Arrays.copyOf(sigPrivateKeyPem, sigPrivateKeyPem.length);
        }

        private byte[] sigCertDer() {
            return Arrays.copyOf(sigCertDer, sigCertDer.length);
        }
    }

    /**
     * Whether only the {@link RosterEntry} entries should be set in a network resource.
     */
    public enum OnlyRoster {
        YES,
        NO
    }
}
