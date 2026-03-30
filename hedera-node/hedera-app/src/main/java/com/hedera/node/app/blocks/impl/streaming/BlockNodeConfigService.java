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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BlockNodeConfigService {

    private static final Logger logger = LogManager.getLogger(BlockNodeConfigService.class);
    private static final String BLOCK_NODES_FILE_NAME = "block-nodes.json";
    private final Path configDirectory;
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicInteger configVersionCounter = new AtomicInteger(0);
    private final ConfigProvider configProvider;
    private final AtomicReference<VersionedBlockNodeConfigurationSet> latestConfig = new AtomicReference<>();
    private final AtomicReference<WatchService> watchServiceRef = new AtomicReference<>();

    BlockNodeConfigService(@NonNull final ConfigProvider configProvider) {
        this.configDirectory = FileUtils.getAbsolutePath(blockNodeConnectionFileDir());
        this.configProvider = requireNonNull(configProvider, "Configuration provider is required");
    }

    public @Nullable VersionedBlockNodeConfigurationSet latestConfiguration() {
        return latestConfig.get();
    }

    /**
     * @return the configuration path (as a String) for the block node connections
     */
    private String blockNodeConnectionFileDir() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .blockNodeConnectionFileDir();
    }

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
            configDirectory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watchServiceRef.set(watchService);
        } catch (final IOException e) {
            logger.error("Failed to start block node configuration watcher", e);
            isActive.set(false);
            return;
        }

        Thread.ofPlatform().name("BlockNodesConfigWatcher").start(new ConfigWatcherTask(watchService));
        logger.info("Block node configuration watcher started");
    }

    public void stop() {
        logger.info("Stopping block node configuration watcher...");

        isActive.set(false);

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

        for (final BlockNodeConfig nodeConfig : connectionInfo.nodes()) {
            try {
                nodeConfigs.add(BlockNodeConfiguration.from(nodeConfig, defaultHardLimitBytes));
            } catch (final RuntimeException e) {
                logger.warn("Failed to parse block node configuration; skipping block node (config={})", nodeConfig, e);
            }
        }

        final long version = configVersionCounter.incrementAndGet();
        final VersionedBlockNodeConfigurationSet versionedConfigSet = new VersionedBlockNodeConfigurationSet(version, nodeConfigs);
        latestConfig.set(versionedConfigSet);

        if (logger.isInfoEnabled()) {
            final StringBuilder sb = new StringBuilder("Block node configuration loaded (version: ").append(version).append(")\n");
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

    private class ConfigWatcherTask implements Runnable {

        private final WatchService watchService;

        ConfigWatcherTask(@NonNull final WatchService watchService) {
            this.watchService = requireNonNull(watchService, "Watch service is required");
        }

        @Override
        public void run() {
            while (isActive.get()) {
                WatchKey key = null;

                try {
                    key = watchService.take();

                    for (final WatchEvent<?> event : key.pollEvents()) {
                        final WatchEvent.Kind<?> kind = event.kind();
                        final Object ctx = event.context();

                        if (ctx instanceof final Path changed && BLOCK_NODES_FILE_NAME.equals(changed.toString())) {
                            logger.info("Detected {} event for {}", kind.name(), changed);
                            loadConfiguration();
                        }
                    }

                } catch (final InterruptedException | ClosedWatchServiceException e) {
                    logger.warn("Configuration watcher interrupted or closed; exiting watcher loop", e);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    stop();
                } catch (final Exception e) {
                    logger.warn("Error encountered in configuration watcher (ignoring)", e);
                } finally {
                    // Always reset the key to continue watching for events, even if an exception occurred
                    if (key != null && !key.reset()) {
                        logger.warn("WatchKey could not be reset; exiting watcher loop");
                        stop();
                    }
                }
            }
        }
    }
}
