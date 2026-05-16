// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import static com.hedera.pbj.runtime.Codec.DEFAULT_MAX_DEPTH;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateUtils.roundOf;

import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.roster.RosterRetriever;

/**
 * A {@link StartupNetworks} implementation that loads {@link Network} information from a
 * working directory on disk.
 */
public class DiskStartupNetworks implements StartupNetworks {
    private static final Logger log = LogManager.getLogger(DiskStartupNetworks.class);

    public static final String ARCHIVE = ".archive";
    public static final String GENESIS_NETWORK_JSON = "genesis-network.json";
    public static final String OVERRIDE_NETWORK_JSON = "override-network.json";
    public static final Pattern ROUND_DIR_PATTERN = Pattern.compile("\\d+");
    private static final int STARTUP_NETWORK_JSON_MAX_FIELD_SIZE = 64 * 1024 * 1024;

    private final ConfigProvider configProvider;

    private boolean isArchived = false;

    @Nullable
    private Long lastOverrideRoundNumber = null;

    /**
     * The types of network information that could be exported to disk.
     */
    public enum InfoType {
        ROSTER,
        NODE_DETAILS,
        TSS,
    }

    /**
     * The types of network information that could be exported to disk.
     */
    private enum AssetUse {
        GENESIS,
        OVERRIDE,
        MIGRATION,
    }

    private record NetworkLoad(Optional<Network> network, boolean cached) {}

    private final Map<Path, Network> cachedNetworks = new HashMap<>();

    public DiskStartupNetworks(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider);
    }

    @Override
    public Network genesisNetworkOrThrow(@NonNull final Configuration platformConfig) {
        requireNonNull(platformConfig);
        final var config = configProvider.getConfiguration();
        return loadNetwork(AssetUse.GENESIS, config, GENESIS_NETWORK_JSON)
                .orElseThrow(() -> new IllegalStateException("Genesis network not found"));
    }

    @Override
    public Optional<Network> overrideNetworkFor(final long roundNumber, @NonNull final Configuration platformConfig) {
        if (roundNumber == 0) {
            return Optional.empty();
        }
        final var config = configProvider.getConfiguration();
        final var unscopedNetwork = loadNetwork(AssetUse.OVERRIDE, config, OVERRIDE_NETWORK_JSON);
        if (unscopedNetwork.isPresent()) {
            return unscopedNetwork;
        }
        final var scopedNetwork = loadNetwork(AssetUse.OVERRIDE, config, "" + roundNumber, OVERRIDE_NETWORK_JSON);
        if (scopedNetwork.isPresent()) {
            return scopedNetwork;
        }

        return Optional.empty();
    }

    @Override
    public Optional<Network> lastUsedOverrideNetwork(Configuration platformConfig) {
        return Optional.ofNullable(lastOverrideRoundNumber).flatMap(n -> overrideNetworkFor(n, platformConfig));
    }

    @Override
    public synchronized void setOverrideRound(final long roundNumber) {
        final var config = configProvider.getConfiguration();
        final var path =
                networksPath(config, OVERRIDE_NETWORK_JSON).toAbsolutePath().normalize();
        if (Files.exists(path)) {
            final var cachedNetwork = cachedNetworks.remove(path);
            final var roundDir =
                    networksPath(config, "" + roundNumber).toAbsolutePath().normalize();
            final var scopedPath =
                    roundDir.resolve(OVERRIDE_NETWORK_JSON).toAbsolutePath().normalize();
            try {
                Files.createDirectories(roundDir);
                Files.move(path, scopedPath);
                if (cachedNetwork != null) {
                    cachedNetworks.put(scopedPath, cachedNetwork);
                }
                log.info("Moved override network file to {}", scopedPath);
            } catch (IOException e) {
                if (cachedNetwork != null) {
                    cachedNetworks.put(path, cachedNetwork);
                }
                log.warn("Failed to move override network file", e);
            }
        }
        // Even if above code didn't move a data/config/override-network.json to a
        // data/config/<roundNumber>/override-network.json file, we still need to
        // track the round number we are applying this override roster in; if not,
        // when reconnecting from a <roundNumber> state after *already restarting*
        // from that state we will fail to repeat the post-upgrade transplant
        // dispatches and hit an ISS immediately after restart
        lastOverrideRoundNumber = roundNumber;
    }

    @Override
    public synchronized void clearCachedNetworks() {
        cachedNetworks.clear();
    }

    @Override
    public void archiveStartupNetworks() {
        if (isArchived) {
            return;
        }
        // We only try to archive once, as it is unlikely any error here would be recoverable
        isArchived = true;
        final var config = configProvider.getConfiguration();
        try {
            ensureArchiveDir(config);
        } catch (IOException e) {
            log.warn("Failed to create archive directory", e);
            return;
        }
        archiveIfPresent(config, GENESIS_NETWORK_JSON);
        archiveIfPresent(config, OVERRIDE_NETWORK_JSON);
        try (final var dirStream = Files.list(networksPath(config))) {
            dirStream
                    .filter(Files::isDirectory)
                    .filter(dir -> ROUND_DIR_PATTERN
                            .matcher(dir.getFileName().toString())
                            .matches())
                    .forEach(dir -> {
                        archiveIfPresent(config, dir.getFileName().toString(), OVERRIDE_NETWORK_JSON);
                        if (!dir.toFile().delete()) {
                            log.warn("Failed to delete round override network directory {}", dir);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list round override network files", e);
        }
        clearCachedNetworks();
    }

    @Override
    public Network migrationNetworkOrThrow(@NonNull final Configuration platformConfig) {
        requireNonNull(platformConfig);
        final var config = configProvider.getConfiguration();
        return loadNetwork(AssetUse.MIGRATION, config, OVERRIDE_NETWORK_JSON)
                .orElseThrow(() -> new IllegalStateException("Transplant network not found"));
    }

    /**
     * Writes a JSON representation of the {@link Network} information in the given state to a given path.
     *
     * @param state the state to write network information from.
     * @param path the path to write the JSON network information to.
     */
    public static void writeNetworkInfo(
            @NonNull final State state, @NonNull final Path path, @NonNull final Set<InfoType> infoTypes) {
        writeNetworkInfo(state, path, withoutTss(infoTypes), null, -1L);
    }

    /**
     * Writes a JSON representation of the {@link Network} information in the given state to a given path.
     *
     * @param state the state to write network information from.
     * @param path the path to write the JSON network information to.
     * @param infoTypes the types of network information to include
     * @param config the configuration to use when including local TSS key material
     * @param selfNodeId the node id whose local private key material should be updated in the JSON file
     */
    public static void writeNetworkInfo(
            @NonNull final State state,
            @NonNull final Path path,
            @NonNull final Set<InfoType> infoTypes,
            @Nullable final Configuration config,
            final long selfNodeId) {
        requireNonNull(state);
        requireNonNull(path);
        requireNonNull(infoTypes);
        networkInfoFrom(state, infoTypes).ifPresent(network -> {
            if (infoTypes.contains(InfoType.TSS)) {
                tryToExportWithTssMetadata(state, network, path, config, selfNodeId);
            } else {
                tryToExport(network, path);
            }
        });
    }

    private static Optional<Network> networkInfoFrom(
            @NonNull final State state, @NonNull final Set<InfoType> infoTypes) {
        final var entityIdStore = new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
        final var nodeStore =
                new ReadableNodeStoreImpl(state.getReadableStates(AddressBookService.NAME), entityIdStore);
        final long round = roundOf(state);
        return Optional.ofNullable(RosterRetriever.retrieveActive(state, round)).map(activeRoster -> {
            final var network = Network.newBuilder();
            final List<NodeMetadata> nodeMetadata = new ArrayList<>();
            activeRoster.rosterEntries().forEach(entry -> {
                final var node = requireNonNull(nodeStore.get(entry.nodeId()));
                nodeMetadata.add(new NodeMetadata(
                        infoTypes.contains(InfoType.ROSTER) ? entry : null,
                        infoTypes.contains(InfoType.NODE_DETAILS) ? node : null));
            });
            network.nodeMetadata(nodeMetadata);
            return network.build();
        });
    }

    private static EnumSet<InfoType> withoutTss(@NonNull final Set<InfoType> infoTypes) {
        requireNonNull(infoTypes);
        final EnumSet<InfoType> effectiveInfoTypes =
                infoTypes.isEmpty() ? EnumSet.noneOf(InfoType.class) : EnumSet.copyOf(infoTypes);
        effectiveInfoTypes.remove(InfoType.TSS);
        return effectiveInfoTypes;
    }

    /**
     * Attempts to export the given {@link Network} to the given path.
     * @param network the network to export
     * @param path the path to export the network to
     */
    public static void tryToExport(@NonNull final Network network, @NonNull final Path path) {
        requireNonNull(network);
        requireNonNull(path);
        try {
            final var absolutePath = path.toAbsolutePath();
            Files.createDirectories(requireNonNull(absolutePath.getParent()));
            final var tmpPath = Files.createTempFile(
                    absolutePath.getParent(), absolutePath.getFileName().toString(), ".tmp");
            try {
                try (final var fout = Files.newOutputStream(tmpPath)) {
                    Network.JSON.write(network, new WritableStreamingData(fout));
                }
                try {
                    Files.move(
                            tmpPath, absolutePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignore) {
                    Files.move(tmpPath, absolutePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmpPath);
            }
        } catch (IOException e) {
            log.warn("Failed to write network info", e);
        }
    }

    private static void tryToExportWithTssMetadata(
            @NonNull final State state,
            @NonNull final Network network,
            @NonNull final Path path,
            @Nullable final Configuration config,
            final long selfNodeId) {
        requireNonNull(state);
        requireNonNull(network);
        requireNonNull(path);
        if (config == null || selfNodeId < 0) {
            log.warn("Cannot include TSS metadata without configuration and self node id");
            tryToExport(network, path);
            return;
        }
        final var absolutePath = path.toAbsolutePath();
        final var lockPath = lockPathFor(absolutePath);
        try {
            Files.createDirectories(requireNonNull(lockPath.getParent()));
            try (final var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    final FileLock ignored = channel.lock()) {
                final var existingNetwork = loadNetworkFrom(absolutePath).orElse(null);
                final var enrichedNetwork =
                        TssStartupNetworks.enrichFromState(state, network, config, selfNodeId, existingNetwork);
                tryToExport(enrichedNetwork, absolutePath);
            }
        } catch (IOException e) {
            log.warn("Failed to write network info with TSS metadata", e);
        }
    }

    private static Path lockPathFor(@NonNull final Path path) {
        return path.resolveSibling(path.getFileName().toString() + ".lock");
    }

    /**
     * Attempts to load a {@link Network} from a given file in the directory whose relative path is given
     * by the provided {@link Configuration}.
     *
     * @param use the use of the network file
     * @param config the configuration to use to determine the location of the network file
     * @param segments the path segments of the file to load the network from
     * @return the loaded network, if it was found and successfully loaded
     */
    private Optional<Network> loadNetwork(
            @NonNull final AssetUse use, @NonNull final Configuration config, @NonNull final String... segments) {
        final var path = networksPath(config, segments).toAbsolutePath().normalize();
        log.info("Checking for {} network info at {}", use, path.toAbsolutePath());
        final var load = loadNetworkWithCache(path);
        load.network()
                .ifPresentOrElse(
                        network -> log.info(
                                "  -> {} {} network info for N={} nodes from {}",
                                load.cached() ? "Reused cached" : "Parsed",
                                use,
                                network.nodeMetadata().size(),
                                path.toAbsolutePath()),
                        () -> log.info("  -> N/A"));
        return load.network();
    }

    private synchronized NetworkLoad loadNetworkWithCache(@NonNull final Path path) {
        requireNonNull(path);
        if (!Files.exists(path)) {
            cachedNetworks.remove(path);
            return new NetworkLoad(Optional.empty(), false);
        }
        final var cachedNetwork = cachedNetworks.get(path);
        if (cachedNetwork != null) {
            return new NetworkLoad(Optional.of(cachedNetwork), true);
        }
        final var network = loadNetworkFrom(path);
        network.ifPresent(parsedNetwork -> cachedNetworks.put(path, parsedNetwork));
        return new NetworkLoad(network, false);
    }

    /**
     * Attempts to load a {@link Network} from a given file.
     *
     * @param path the path to the file to load the network from
     * @return the loaded network, if it was found and successfully loaded
     */
    public static Optional<Network> loadNetworkFrom(@NonNull final Path path) {
        if (Files.exists(path)) {
            try (final var fin = Files.newInputStream(path)) {
                return Optional.of(Network.JSON.parse(
                        new ReadableStreamingData(fin),
                        true,
                        false,
                        DEFAULT_MAX_DEPTH,
                        STARTUP_NETWORK_JSON_MAX_FIELD_SIZE));
            } catch (Exception e) {
                log.warn("Failed to load {} network info from {}", path.toAbsolutePath(), e);
            }
        }
        return Optional.empty();
    }

    /**
     * Attempts to archive the given segments in the given configuration.
     *
     * @param segments the segments to archive
     */
    private static void archiveIfPresent(Path basePath, @NonNull final String... segments) {
        try {
            final var path = Paths.get(basePath.toAbsolutePath().toString(), segments);
            if (Files.exists(path)) {
                final var archiveSegments =
                        Stream.concat(Stream.of(ARCHIVE), Stream.of(segments)).toArray(String[]::new);
                final var dest = Paths.get(basePath.toAbsolutePath().toString(), archiveSegments);
                createIfAbsent(dest.getParent());
                Files.move(path, dest);
            }
        } catch (IOException e) {
            log.warn("Failed to archive {}", segments, e);
        }
    }

    private static void archiveIfPresent(@NonNull final Configuration config, @NonNull final String... segments) {
        archiveIfPresent(networksPath(config), segments);
    }

    /**
     * Ensures that the archive directory exists in the given configuration.
     *
     * @param config the configuration to ensure the archive directory exists in
     */
    private static void ensureArchiveDir(@NonNull final Configuration config) throws IOException {
        createIfAbsent(networksPath(config, ARCHIVE));
    }

    /**
     * Creates the given path as a directory if it does not already exist.
     *
     * @param path the path to the directory create if it does not already exist
     */
    private static void createIfAbsent(@NonNull final Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectory(path);
        }
    }

    /**
     * Gets the path to the directory containing network files.
     *
     * @param config the configuration to use to determine the location of the network files
     * @return the path to the directory containing network files
     */
    private static Path networksPath(@NonNull final Configuration config, @NonNull final String... segments) {
        return Paths.get(config.getConfigData(NetworkAdminConfig.class).upgradeSysFilesLoc(), segments);
    }
}
