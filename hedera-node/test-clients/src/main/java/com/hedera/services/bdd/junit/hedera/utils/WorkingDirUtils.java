// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.utils;

import static com.hedera.node.app.info.DiskStartupNetworks.GENESIS_NETWORK_JSON;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.config.converter.SemanticVersionConverter;
import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.props.JutilPropertySource;
import com.swirlds.platform.crypto.CryptoStatic;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

public class WorkingDirUtils {
    private static final Path BASE_WORKING_LOC = Path.of("./build");
    private static final String DEFAULT_SCOPE = "hapi";
    private static final String KEYS_FOLDER = "keys";
    private static final String CONFIG_FOLDER = "config";
    private static final String LOG4J2_XML = "log4j2.xml";
    private static final String SETTINGS_TXT = "settings.txt";
    private static final String PROJECT_BOOTSTRAP_ASSETS_LOC = "hedera-node/configuration/dev";
    private static final String TEST_CLIENTS_BOOTSTRAP_ASSETS_LOC = "../configuration/dev";
    private static final X509Certificate SIG_CERT;
    public static final Bytes VALID_CERT;

    static {
        final var selfId = NodeId.of(1);
        final Map<NodeId, KeysAndCerts> sigAndCerts;
        try {
            sigAndCerts = CryptoStatic.generateKeysAndCerts(List.of(selfId));
        } catch (ExecutionException | InterruptedException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
        SIG_CERT = requireNonNull(sigAndCerts.get(selfId).sigCert());
        try {
            VALID_CERT = Bytes.wrap(SIG_CERT.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

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
    private static final Pattern LOAD_KEYS_FROM_PFX_PATTERN =
            Pattern.compile("^(\\s*loadKeysFromPfxFiles\\s*,\\s*)(\\S+)(.*)$");

    private WorkingDirUtils() {
        throw new UnsupportedOperationException("Utility Class");
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
        syncPreGeneratedGossipKeys(workingDir, nodeId);
        // Update the log4j2.xml file with the correct output directory
        updateLog4j2XmlOutputDir(workingDir, nodeId);
    }

    public static void recreateWorkingDir(
            @NonNull final Path workingDir, @NonNull final String configTxt, final long nodeId) {
        recreateWorkingDir(workingDir, configTxt, nodeId, Map.of());
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
     * Uses the candidate roster when present; otherwise falls back to the current or archived {@code config.txt}.
     *
     * @param workingDir the working directory
     * @param nodeId the local node id
     * @throws IllegalStateException if the roster is missing or key material cannot be satisfied
     */
    public static void syncPreGeneratedGossipKeys(@NonNull final Path workingDir, final long nodeId) {
        final var nodeIds = nodeIdsForGossipKeys(workingDir);
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new IllegalStateException("Unable to determine roster node IDs for " + workingDir);
        }
        if (!nodeIds.contains(nodeId)) {
            throw new IllegalStateException(
                    "Local node id " + nodeId + " is not present in roster node IDs " + nodeIds + " for " + workingDir);
        }
        final var preGeneratedKeys = AddressBookUtils.preGeneratedGossipKeysForNodeIds(nodeIds);
        writePreGeneratedGossipKeys(workingDir.resolve(DATA_DIR).resolve(KEYS_FOLDER), nodeId, preGeneratedKeys);
        setLoadKeysFromPfxFiles(workingDir, true);
    }

    private static @Nullable Set<Long> nodeIdsForGossipKeys(@NonNull final Path workingDir) {
        final var candidateRosterNodeIds = nodeIdsFromCandidateRoster(workingDir);
        if (candidateRosterNodeIds != null) {
            return candidateRosterNodeIds;
        }
        return nodeIdsFromConfig(workingDir);
    }

    private static @Nullable Set<Long> nodeIdsFromCandidateRoster(@NonNull final Path workingDir) {
        final var candidateRosterPath = workingDir.resolve(CANDIDATE_ROSTER_JSON);
        if (!Files.exists(candidateRosterPath)) {
            return null;
        }
        try (final var fin = Files.newInputStream(candidateRosterPath)) {
            final var network = Network.JSON.parse(new ReadableStreamingData(fin));
            return network.nodeMetadata().stream()
                    .map(NodeMetadata::rosterEntryOrThrow)
                    .map(RosterEntry::nodeId)
                    .collect(Collectors.toSet());
        } catch (IOException | com.hedera.pbj.runtime.ParseException e) {
            throw new IllegalStateException("Failed to parse candidate roster at " + candidateRosterPath, e);
        }
    }

    private static @Nullable Set<Long> nodeIdsFromConfig(@NonNull final Path workingDir) {
        Path configPath = workingDir.resolve(CONFIG_TXT);
        if (!Files.exists(configPath)) {
            configPath = workingDir.resolve(ARCHIVE).resolve(CONFIG_TXT);
        }
        if (!Files.exists(configPath)) {
            return null;
        }
        final var configTxt = readStringUnchecked(configPath);
        final AddressBook synthBook;
        try {
            synthBook = com.swirlds.platform.system.address.AddressBookUtils.parseAddressBookText(configTxt);
        } catch (ParseException e) {
            throw new IllegalArgumentException(e);
        }
        return IntStream.range(0, synthBook.getSize())
                .mapToObj(i -> synthBook.getNodeId(i).id())
                .collect(Collectors.toSet());
    }

    /**
     * Writes pre-generated signing keys and certificates into the given keys directory.
     *
     * @param keysDir the keys directory for the node
     * @param nodeId the local node id
     * @param keyMaterialByNode the pre-generated key material for all nodes
     */
    private static void writePreGeneratedGossipKeys(
            @NonNull final Path keysDir,
            final long nodeId,
            @NonNull final Map<Long, AddressBookUtils.GossipKeyMaterial> keyMaterialByNode) {
        requireNonNull(keysDir);
        requireNonNull(keyMaterialByNode);
        createDirectoriesUnchecked(keysDir);
        for (final var entry : keyMaterialByNode.entrySet()) {
            final var nodeName = RosterUtils.formatNodeName(entry.getKey());
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
     * Whether only the {@link RosterEntry} entries should be set in a network resource.
     */
    public enum OnlyRoster {
        YES,
        NO
    }
}
