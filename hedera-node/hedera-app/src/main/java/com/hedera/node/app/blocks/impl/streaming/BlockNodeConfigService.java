// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.io.utility.FileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
     * Holder for the file watcher service to detect configuration file changes.
     */
    private final AtomicReference<WatchService> watchServiceRef = new AtomicReference<>();

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
     * @return the latest configuration for all possible block nodes, else null if no configuration exists
     */
    public @Nullable VersionedBlockNodeConfigurationSet latestConfiguration() {
        return latestConfigRef.get();
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
        final List<BlockNodeConfiguration> nodeConfigs = new ArrayList<>();

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

        final long defaultHardLimitBytes = configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .defaultMessageHardLimitBytes();

        final Map<String, AtomicInteger> hostCounters = new HashMap<>();
        for (final BlockNodeConfig nodeConfig : connectionInfo.nodes()) {
            try {
                nodeConfigs.add(BlockNodeConfiguration.from(nodeConfig, defaultHardLimitBytes));
                hostCounters
                        .computeIfAbsent(
                                nodeConfig.address() + ":" + nodeConfig.streamingPort(), _ -> new AtomicInteger())
                        .incrementAndGet();
            } catch (final RuntimeException e) {
                logger.warn("Failed to parse block node configuration; skipping block node (config={})", nodeConfig, e);
            }
        }

        if (nodeConfigs.isEmpty()) {
            logger.warn("No block node configurations successfully processed; skipping configuration update");
            return;
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
            return;
        }

        final long version = configVersionCounter.incrementAndGet();
        final VersionedBlockNodeConfigurationSet versionedConfigSet =
                new VersionedBlockNodeConfigurationSet(version, nodeConfigs);
        latestConfigRef.set(versionedConfigSet);

        if (logger.isInfoEnabled()) {
            final StringBuilder sb = new StringBuilder("Block node configuration loaded (version: ")
                    .append(version)
                    .append(")\n");
            nodeConfigs.sort(Comparator.comparingInt(BlockNodeConfiguration::priority));
            final Iterator<BlockNodeConfiguration> it = nodeConfigs.iterator();
            while (it.hasNext()) {
                sb.append("  ").append(it.next());
                if (it.hasNext()) {
                    sb.append("\n");
                }
            }

            logger.info("{}", sb);
        }
    }

    /**
     * Task that checks for configuration file modifications on disk and upon detecting a change, updates the
     * configuration held in memory.
     */
    private class ConfigWatcherTask implements Runnable {

        @Override
        public void run() {
            while (isActive.get()) {
                WatchKey key = null;

                try {
                    key = watchServiceRef.get().take();

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
