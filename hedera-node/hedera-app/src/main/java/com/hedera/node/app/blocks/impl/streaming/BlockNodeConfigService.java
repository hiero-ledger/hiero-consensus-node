// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.addressbook.RegisteredServiceEndpoint;
import com.hedera.hapi.node.addressbook.RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.RegisteredNodeEndpointResolver;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.file.FileUtils;

/**
 * Service for retrieving block node configurations from disk. This service will launch a file watcher process that will
 * dynamically load newer configurations upon detecting changes.
 */
@Singleton
public class BlockNodeConfigService {

    private static final Logger logger = LogManager.getLogger(BlockNodeConfigService.class);

    /**
     * The name of the block node configuration file.
     */
    private static final String BLOCK_NODES_FILE_NAME = "block-nodes.json";
    /**
     * The directory where the block node configuration file will be, if it exists.
     */
    private final Path configDirectory;
    /**
     * Flag indicating whether this service is active or not.
     */
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    /**
     * Counter used to track/identify configuration versions.
     */
    private final AtomicInteger configVersionCounter = new AtomicInteger(0);
    /**
     * Mechanism to access application configurations.
     */
    private final ConfigProvider configProvider;
    /**
     * Holder for the most recent configuration loaded from disk. This may be null if no configuration is present
     * on disk. Technically, this version of the configuration may not be the latest on disk. In cases where the on-disk
     * configuration fails to be parsed/read, then the latest configuration held by this reference will not be updated.
     * In other words, the configuration held by this reference represents the most recent, successfully loaded
     * configuration.
     */
    private final AtomicReference<VersionedBlockNodeConfigurationSet> latestConfigRef = new AtomicReference<>();
    /**
     * The most recent {@link BlockNodeConnectionInfo} that was successfully parsed from disk. Retained so that the
     * service can re-resolve {@code registeredNodeId} entries against the latest state without re-reading the file.
     * Null until the first successful disk load.
     */
    private final AtomicReference<BlockNodeConnectionInfo> latestSourceRef = new AtomicReference<>();
    /**
     * Holder for the file watcher service to detect configuration file changes.
     */
    private final AtomicReference<WatchService> watchServiceRef = new AtomicReference<>();
    /**
     * Resolver used to look up {@link RegisteredNode} instances when a configuration entry references a
     * {@code registeredNodeId}. Defaults to {@link RegisteredNodeEndpointResolver#NO_OP} so the service can be
     * exercised without a running state (e.g. in unit tests of the loader itself).
     */
    private final AtomicReference<RegisteredNodeEndpointResolver> registeredNodeResolverRef =
            new AtomicReference<>(RegisteredNodeEndpointResolver.NO_OP);

    /**
     * Creates a new configuration monitor service.
     *
     * @param configProvider the application configuration provider to use
     */
    @Inject
    public BlockNodeConfigService(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider, "Configuration provider is required");

        final String configDir = configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .blockNodeConnectionFileDir();

        this.configDirectory = FileUtils.getAbsolutePath(configDir);
    }

    /**
     * Sets the {@link RegisteredNodeEndpointResolver} used to resolve {@code registeredNodeId} references in
     * {@code block-nodes.json}. May be called multiple times; the latest resolver applies to subsequent reloads.
     *
     * @param resolver the resolver to use; pass {@link RegisteredNodeEndpointResolver#NO_OP} to disable resolution
     */
    public void setRegisteredNodeResolver(@NonNull final RegisteredNodeEndpointResolver resolver) {
        registeredNodeResolverRef.set(requireNonNull(resolver, "resolver must not be null"));
    }

    /**
     * @return the latest configuration for all possible block nodes, else null if no configuration exists
     */
    public @Nullable VersionedBlockNodeConfigurationSet latestConfiguration() {
        return latestConfigRef.get();
    }

    /**
     * Re-resolves any entries in the most recently loaded {@code block-nodes.json} that reference a
     * {@code registeredNodeId}, using the current state via the configured resolver. If the resolution produces a
     * different set of configurations from the one currently held, the version counter is bumped and the latest
     * configuration reference is updated so that downstream consumers (e.g. the connection manager) can pick up the
     * change.
     *
     * <p>This is intended to be called on a schedule by the block-stream connection monitor so that registered-node
     * endpoint changes propagate without requiring an edit to {@code block-nodes.json}.
     *
     * @return {@code true} if the configuration was updated as a result of re-resolution, {@code false} otherwise
     */
    public boolean revalidateRegisteredNodes() {
        final BlockNodeConnectionInfo source = latestSourceRef.get();
        if (source == null || source.nodes().isEmpty()) {
            return false;
        }
        // only do work if at least one entry actually uses a registered node id
        final boolean anyHasRegisteredId = source.nodes().stream().anyMatch(n -> n.registeredNodeId() != null);
        if (!anyHasRegisteredId) {
            return false;
        }
        return rebuildAndMaybePublish(source, /* announceNoChange= */ false);
    }

    /**
     * Starts the configuration service. This will attempt to load the initial configuration from disk, if one exists,
     * then creates a file watcher to detect modifications to the configuration on disk.
     */
    public void start() {
        if (!isActive.compareAndSet(false, true)) {
            logger.debug("Block node configuration watcher is already started");
            return;
        }

        logger.info("Starting block node configuration watcher...");

        // Perform initial load of the configuration
        try {
            loadConfiguration();
        } catch (final RuntimeException e) {
            logger.warn("Failed to load initial block node configuration (ignoring)", e);
        }

        // Start the watcher for config changes
        final WatchService watchService;

        try {
            watchService = configDirectory.getFileSystem().newWatchService();
            configDirectory.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watchServiceRef.set(watchService);
        } catch (final IOException e) {
            logger.error("Failed to start block node configuration watcher", e);
            isActive.set(false);
            return;
        }

        Thread.ofPlatform().name("BlockNodesConfigWatcher").daemon().start(new ConfigWatcherTask());
        logger.info("Block node configuration watcher started");
    }

    /**
     * Stops the configuration service. This will also clear any previously held configuration and stop the file watcher
     * that is monitoring configuration file changes.
     */
    public void shutdown() {
        if (!isActive.compareAndSet(true, false)) {
            return;
        }

        logger.info("Stopping block node configuration watcher...");

        latestConfigRef.set(null);
        latestSourceRef.set(null);
        configVersionCounter.incrementAndGet();

        final WatchService watchService = watchServiceRef.getAndSet(null);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (final IOException e) {
                logger.debug("Error while closing watch service (ignoring)", e);
            }
        }

        logger.info("Block node configuration watcher stopped");
    }

    /**
     * Loads the block node configuration from disk. In general, if there are issues parsing the configuration, the
     * method will not fail. Instead, the failures are logged and the latest configuration is not updated.
     */
    private void loadConfiguration() {
        final Path path = configDirectory.resolve(BLOCK_NODES_FILE_NAME);
        final BlockNodeConnectionInfo connectionInfo;

        try {
            if (!Files.exists(path)) {
                logger.warn("Block node configuration file does not exist at {}", path);
                return;
            }

            final byte[] bytes = Files.readAllBytes(path);
            connectionInfo = BlockNodeConnectionInfo.JSON.parse(Bytes.wrap(bytes));
        } catch (final IOException | ParseException e) {
            logger.warn("Failed to read/parse block node configuration from {}", path, e);
            return;
        }

        latestSourceRef.set(connectionInfo);

        if (connectionInfo.nodes().isEmpty()) {
            // there is nothing in the configuration file - treat this as a valid configuration that effectively
            // disables any and all active block nodes we already know about
            final int newVersionNumber = configVersionCounter.incrementAndGet();
            final VersionedBlockNodeConfigurationSet newConfig =
                    new VersionedBlockNodeConfigurationSet(newVersionNumber, List.of());
            latestConfigRef.set(newConfig);
            logger.info("Block node configuration loaded (version: {}) - empty configuration", newVersionNumber);
            return;
        }

        rebuildAndMaybePublish(connectionInfo, /* announceNoChange= */ true);
    }

    /**
     * Walks the supplied source config, applying resolution, and either publishes a new
     * {@link VersionedBlockNodeConfigurationSet} (when the result differs from the current one) or leaves the existing
     * configuration in place.
     *
     * @param source the parsed source from disk to rebuild from
     * @param announceNoChange if {@code true}, log a warning when no configurations could be successfully processed; if
     *                         {@code false}, stay quiet (used by the re-resolve path where no change is normal)
     * @return {@code true} if a new versioned configuration was published, {@code false} otherwise
     */
    private boolean rebuildAndMaybePublish(
            @NonNull final BlockNodeConnectionInfo source, final boolean announceNoChange) {
        final long defaultHardLimitBytes = configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .defaultMessageHardLimitBytes();
        final RegisteredNodeEndpointResolver resolver = registeredNodeResolverRef.get();

        final List<BlockNodeConfiguration> nodeConfigs = new ArrayList<>();
        final Map<String, AtomicInteger> hostCounters = new HashMap<>();
        for (final BlockNodeConfig nodeConfig : source.nodes()) {
            try {
                final BlockNodeConfiguration cfg = buildConfiguration(nodeConfig, defaultHardLimitBytes, resolver);
                if (cfg == null) {
                    // resolution failed in a way that already logged; skip silently
                    continue;
                }
                nodeConfigs.add(cfg);
                hostCounters
                        .computeIfAbsent(cfg.address() + ":" + cfg.streamingPort(), _ -> new AtomicInteger())
                        .incrementAndGet();
            } catch (final RuntimeException e) {
                logger.warn(
                        "Failed to parse block node configuration; skipping block node (config: {})", nodeConfig, e);
            }
        }

        if (nodeConfigs.isEmpty()) {
            if (announceNoChange) {
                logger.warn("No block node configurations successfully processed; skipping configuration update");
            }
            return false;
        }

        // check for duplicates
        boolean duplicatesFound = false;
        for (final Map.Entry<String, AtomicInteger> hostCounterEntry : hostCounters.entrySet()) {
            if (hostCounterEntry.getValue().get() > 1) {
                duplicatesFound = true;
                logger.warn("Duplicate configurations found for host: {}", hostCounterEntry.getKey());
            }
        }

        if (duplicatesFound) {
            logger.warn("One or more block node hosts have duplicate configurations; skipping configuration update");
            return false;
        }

        // skip if the rebuild produced the same list of configurations as what we already have
        final VersionedBlockNodeConfigurationSet existing = latestConfigRef.get();
        if (existing != null && existing.configs().equals(nodeConfigs)) {
            return false;
        }

        final long version = configVersionCounter.incrementAndGet();
        final VersionedBlockNodeConfigurationSet versionedConfigSet =
                new VersionedBlockNodeConfigurationSet(version, nodeConfigs);
        latestConfigRef.set(versionedConfigSet);

        if (logger.isInfoEnabled()) {
            final List<BlockNodeConfiguration> nodeConfigsCopy = new ArrayList<>(nodeConfigs);
            final StringBuilder sb = new StringBuilder("Block node configuration loaded (version: ")
                    .append(version)
                    .append(")\n");
            nodeConfigsCopy.sort(Comparator.comparingInt(BlockNodeConfiguration::priority));
            final Iterator<BlockNodeConfiguration> it = nodeConfigsCopy.iterator();
            while (it.hasNext()) {
                sb.append("  ").append(it.next());
                if (it.hasNext()) {
                    sb.append("\n");
                }
            }

            logger.info("{}", sb);
        }
        return true;
    }

    /**
     * Builds a {@link BlockNodeConfiguration} from a parsed entry, applying registered-node-id resolution when
     * applicable.
     *
     * <p>Returns {@code null} if the entry references a {@code registeredNodeId} that cannot be resolved and the
     * entry does not carry enough explicit values to stand on its own; the caller treats this as a skip.
     */
    private @Nullable BlockNodeConfiguration buildConfiguration(
            @NonNull final BlockNodeConfig nodeConfig,
            final long defaultHardLimitBytes,
            @NonNull final RegisteredNodeEndpointResolver resolver) {
        final Long registeredId = nodeConfig.registeredNodeId();
        if (registeredId == null) {
            return BlockNodeConfiguration.from(nodeConfig, defaultHardLimitBytes);
        }

        final long registeredNodeId = registeredId;
        final String explicitAddress = nodeConfig.address() == null ? "" : nodeConfig.address();
        final int explicitStreamingPort = nodeConfig.streamingPort();
        final Integer explicitServicePort = nodeConfig.servicePort();

        final boolean stateAvailable = resolver.isStateAvailable();
        final RegisteredNode registeredNode = stateAvailable ? resolver.resolve(registeredNodeId) : null;

        String resolvedStreamingHost = null;
        String resolvedServiceHost = null;
        Integer resolvedStreamingPort = null;
        Integer resolvedServicePort = null;
        if (registeredNode != null) {
            final Optional<RegisteredServiceEndpoint> publishEp = pickEndpoint(registeredNode, BlockNodeApi.PUBLISH);
            final Optional<RegisteredServiceEndpoint> statusEp = pickEndpoint(registeredNode, BlockNodeApi.STATUS);
            resolvedStreamingHost =
                    publishEp.map(BlockNodeConfigService::endpointHost).orElse(null);
            resolvedServiceHost =
                    statusEp.map(BlockNodeConfigService::endpointHost).orElse(null);
            resolvedStreamingPort =
                    publishEp.map(RegisteredServiceEndpoint::port).orElse(null);
            resolvedServicePort = statusEp.map(RegisteredServiceEndpoint::port).orElse(null);
            if (resolvedStreamingHost != null
                    && resolvedServiceHost != null
                    && !resolvedStreamingHost.equals(resolvedServiceHost)) {
                logger.info(
                        "Registered node id {} advertises PUBLISH host '{}' and STATUS host '{}' separately; "
                                + "using each for its respective endpoint",
                        registeredNodeId,
                        resolvedStreamingHost,
                        resolvedServiceHost);
            }
        } else if (stateAvailable) {
            logger.info(
                    "Block node configuration references registered node id {} which is not in state; "
                            + "falling back to any explicit address/port values",
                    registeredNodeId);
        } else {
            logger.info(
                    "Block node configuration references registered node id {} but state is not yet available; "
                            + "using explicit address/port values for this load",
                    registeredNodeId);
        }

        // streaming host falls back to resolved PUBLISH host, then to resolved STATUS host
        final String resolvedStreamingHostOrAny =
                resolvedStreamingHost != null ? resolvedStreamingHost : resolvedServiceHost;
        final String finalStreamingHost = !explicitAddress.isBlank() ? explicitAddress : resolvedStreamingHostOrAny;
        // service host: explicit > resolved STATUS > resolved PUBLISH (fallback to whatever streaming uses)
        final String resolvedServiceHostOrAny =
                resolvedServiceHost != null ? resolvedServiceHost : resolvedStreamingHost;
        final String finalServiceHost = !explicitAddress.isBlank() ? explicitAddress : resolvedServiceHostOrAny;
        final int finalStreamingPort = explicitStreamingPort > 0
                ? explicitStreamingPort
                : (resolvedStreamingPort != null ? resolvedStreamingPort : -1);
        final int finalServicePort = explicitServicePort != null
                ? explicitServicePort
                : (resolvedServicePort != null ? resolvedServicePort : -1);

        if (finalStreamingHost == null || finalStreamingHost.isBlank() || finalStreamingPort <= 0) {
            logger.info(
                    "Skipping block node configuration for registered node id {}: no usable address or streaming port "
                            + "(explicit address='{}', explicit streamingPort={}, resolved streamingHost='{}', "
                            + "resolved streamingPort={})",
                    registeredNodeId,
                    explicitAddress,
                    explicitStreamingPort,
                    resolvedStreamingHost,
                    resolvedStreamingPort);
            return null;
        }

        // log differences between explicit values and what the registered node advertised
        if (!explicitAddress.isBlank()
                && resolvedStreamingHostOrAny != null
                && !explicitAddress.equals(resolvedStreamingHostOrAny)) {
            logger.info(
                    "Registered node id {} advertises address '{}' but block-nodes.json explicitly sets '{}'; "
                            + "using the explicit value",
                    registeredNodeId,
                    resolvedStreamingHostOrAny,
                    explicitAddress);
        }
        if (resolvedStreamingPort != null
                && explicitStreamingPort > 0
                && resolvedStreamingPort != explicitStreamingPort) {
            logger.info(
                    "Registered node id {} advertises streaming port {} but block-nodes.json explicitly sets {}; "
                            + "using the explicit value",
                    registeredNodeId,
                    resolvedStreamingPort,
                    explicitStreamingPort);
        }
        if (resolvedServicePort != null
                && explicitServicePort != null
                && !resolvedServicePort.equals(explicitServicePort)) {
            logger.info(
                    "Registered node id {} advertises service port {} but block-nodes.json explicitly sets {}; "
                            + "using the explicit value",
                    registeredNodeId,
                    resolvedServicePort,
                    explicitServicePort);
        }

        final BlockNodeConfiguration.Builder builder = BlockNodeConfiguration.newBuilder()
                .address(finalStreamingHost)
                .serviceAddress(finalServiceHost)
                .streamingPort(finalStreamingPort)
                .servicePort(finalServicePort)
                .registeredNodeId(registeredNodeId);
        BlockNodeConfiguration.populateSharedFields(builder, nodeConfig, defaultHardLimitBytes);
        return builder.build();
    }

    private static @NonNull Optional<RegisteredServiceEndpoint> pickEndpoint(
            @NonNull final RegisteredNode node, @NonNull final BlockNodeApi api) {
        return node.serviceEndpoint().stream()
                .filter(ep ->
                        ep.hasBlockNode() && ep.blockNodeOrThrow().endpointApi().contains(api))
                .findFirst();
    }

    private static @Nullable String endpointHost(@NonNull final RegisteredServiceEndpoint endpoint) {
        if (endpoint.hasDomainName()) {
            final String domain = endpoint.domainName();
            return (domain == null || domain.isBlank()) ? null : domain;
        }
        if (endpoint.hasIpAddress()) {
            final byte[] ip = endpoint.ipAddress().toByteArray();
            try {
                return InetAddress.getByAddress(ip).getHostAddress();
            } catch (final UnknownHostException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Task that checks for configuration file modifications on disk and upon detecting a change, updates the
     * configuration held in memory.
     */
    private class ConfigWatcherTask implements Runnable {

        @Override
        public void run() {
            while (isActive.get()) {
                final WatchService watchService = watchServiceRef.get();
                if (watchService == null) {
                    // If the watch service is null, it likely means the configuration service is shutting down
                    // Thus, if we continue, the isActive check will be triggered and the shutdown should be detected
                    continue;
                }

                WatchKey key = null;

                try {
                    key = watchService.take();

                    for (final WatchEvent<?> event : key.pollEvents()) {
                        final WatchEvent.Kind<?> kind = event.kind();
                        final Object ctx = event.context();

                        if (ctx instanceof final Path changed && BLOCK_NODES_FILE_NAME.equals(changed.toString())) {
                            logger.info("Detected {} event for {}", kind.name(), changed);

                            if (StandardWatchEventKinds.ENTRY_DELETE == kind) {
                                // treat a deletion as a version change
                                final int newVersionNumber = configVersionCounter.incrementAndGet();
                                final VersionedBlockNodeConfigurationSet newConfig =
                                        new VersionedBlockNodeConfigurationSet(newVersionNumber, List.of());
                                latestConfigRef.set(newConfig);
                                latestSourceRef.set(null);
                            } else {
                                loadConfiguration();
                            }
                        }
                    }
                } catch (final InterruptedException | ClosedWatchServiceException e) {
                    logger.warn("Configuration watcher interrupted or closed; exiting watcher loop", e);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    shutdown();
                } catch (final Exception e) {
                    logger.warn("Error encountered in configuration watcher (ignoring)", e);
                } finally {
                    // Always reset the key to continue watching for events, even if an exception occurred
                    if (key != null && !key.reset()) {
                        logger.warn("WatchKey could not be reset; exiting watcher loop");
                        shutdown();
                    }
                }
            }
        }
    }
}
