// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages connections to block nodes, connection lifecycle and node selection.
 * It is also responsible for retrying with exponential backoff if a connection fails.
 */
public class BlockNodeConnectionManager {
    public static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Logger logger = LogManager.getLogger(BlockNodeConnectionManager.class);
    private static final String GRPC_END_POINT =
            BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();
    private static final long RETRY_BACKOFF_MULTIPLIER = 2;

    // Add a random number generator for jitter
    private final Random random = new Random();

    private final Map<BlockNodeConfig, BlockNodeConnection> activeConnections;
    private BlockNodeConfigExtractor blockNodeConfigurations;
    private final BlockStreamStateManager blockStreamStateManager;

    private final Object connectionLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService streamingExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService retryExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Creates a new BlockNodeConnectionManager with the given configuration from disk.
     * @param configProvider the configuration provider
     * @param blockStreamStateManager the block stream state manager
     */
    public BlockNodeConnectionManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockStreamStateManager blockStreamStateManager) {
        requireNonNull(configProvider);
        requireNonNull(blockStreamStateManager);
        this.activeConnections = new ConcurrentHashMap<>();
        this.blockStreamStateManager = blockStreamStateManager;

        final var blockStreamConfig = configProvider.getConfiguration().getConfigData(BlockStreamConfig.class);
        if (!blockStreamConfig.streamToBlockNodes()) {
            return;
        }
        this.blockNodeConfigurations = new BlockNodeConfigExtractor(blockStreamConfig.blockNodeConnectionFileDir());
    }

    /**
     * Attempts to establish connections to block nodes based on priority and configuration.
     */
    private void establishConnections() {
        logger.info("Establishing connections to block nodes");

        List<BlockNodeConfig> availableNodes = blockNodeConfigurations.getAllNodes().stream()
                .filter(node -> !activeConnections.containsKey(node))
                .toList();

        availableNodes.forEach(this::connectToNode);
    }

    private void connectToNode(@NonNull BlockNodeConfig node) {
        synchronized (connectionLock) {
            try {
                BlockNodeConnection connection = new BlockNodeConnection(node, this, blockStreamStateManager);
                connection.establishStream();
                connection.getIsActiveLock().lock();
                try {
                    if (connection.isActive()) {
                        activeConnections.put(node, connection);
                        logger.info("Successfully connected to block node {}:{}", node.address(), node.port());
                    }
                } finally {
                    connection.getIsActiveLock().unlock();
                }
            } catch (Exception e) {
                logger.error("Failed to connect to block node {}:{}", node.address(), node.port(), e);
            }
        }
    }

    private synchronized void disconnectFromNode(@NonNull BlockNodeConfig node) {
        synchronized (connectionLock) {
            BlockNodeConnection connection = activeConnections.remove(node);
            if (connection != null) {
                connection.getIsActiveLock().lock();
                try {
                    connection.close();
                    logger.info("Disconnected from block node {}:{}", node.address(), node.port());
                } finally {
                    connection.getIsActiveLock().unlock();
                }
            }
        }
    }

    /**
     * Handles connection errors from a BlockNodeConnection by removing the failed connection
     * and initiating the reconnection process.
     *
     * @param node the node configuration for the failed connection
     */
    public void handleConnectionError(@NonNull BlockNodeConfig node) {
        synchronized (connectionLock) {
            BlockNodeConnection connection = activeConnections.remove(node);
            if (connection != null) {
                connection.getIsActiveLock().lock();
                try {
                    if (connection.isActive()) {
                        connection.close();
                    }
                } finally {
                    connection.getIsActiveLock().unlock();
                }
            }
        }
    }

    public void scheduleReconnect(@NonNull final BlockNodeConnection connection) {
        requireNonNull(connection);
        
        retryExecutor.execute(() -> {
            synchronized (connectionLock) {
                try {
                    BlockNodeConfig nodeConfig = connection.getNodeConfig();
                    if (!activeConnections.containsKey(nodeConfig)) {
                        connection.getIsActiveLock().lock();
                        try {
                            if (!connection.isActive()) {
                                activeConnections.put(nodeConfig, connection);
                                retry(connection::establishStream, INITIAL_RETRY_DELAY);
                            }
                        } finally {
                            connection.getIsActiveLock().unlock();
                        }
                    }
                } catch (Exception e) {
                    final var node = connection.getNodeConfig();
                    logger.error("Failed to re-establish stream to block node {}:{}: {}", 
                        node.address(), node.port(), e);
                    activeConnections.remove(connection.getNodeConfig());
                }
            }
        });
    }

    /**
     * Retries the given action with exponential backoff.
     *
     * @param action the action to retry
     * @param initialDelay the initial delay before the first retry
     * @param <T> the return type of the action
     */
    public <T> void retry(@NonNull final Supplier<T> action, @NonNull final Duration initialDelay) {
        requireNonNull(action);
        requireNonNull(initialDelay);

        Duration delay = initialDelay;

        while (true) {
            try {
                // Apply jitter: use a random value between 50-100% of the calculated delay
                final long jitteredDelayMs = delay.toMillis() / 2 + random.nextLong(delay.toMillis() / 2 + 1);
                logger.info("Retrying in {} ms", jitteredDelayMs);
                Thread.sleep(jitteredDelayMs);
                action.get();
                return;
            } catch (Exception e) {
                delay = delay.multipliedBy(RETRY_BACKOFF_MULTIPLIER);
            }
        }
    }

    /**
     * Shuts down the connection manager, closing all active connections.
     */
    public void shutdown() {
        scheduler.shutdown();
        retryExecutor.shutdown();
        try {
            boolean awaitTermination = streamingExecutor.awaitTermination(10, TimeUnit.SECONDS);
            if (!awaitTermination) {
                logger.error("Failed to shut down streaming executor within 10 seconds");
            } else {
                logger.info("Successfully shut down streaming executor");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        synchronized (connectionLock) {
            for (BlockNodeConfig node : new ArrayList<>(activeConnections.keySet())) {
                disconnectFromNode(node);
            }
        }
    }

    /**
     * Waits for at least one connection to be established.
     *
     * @param timeout the maximum time to wait
     * @return true if at least one connection was established, false if the timeout elapsed before any connections were established
     */
    public boolean waitForConnection(Duration timeout) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        establishConnections();

        scheduler.scheduleAtFixedRate(
                () -> {
                    synchronized (connectionLock) {
                        if (!activeConnections.isEmpty()) {
                            future.complete(true);
                        } else if (Instant.now().isAfter(Instant.now().plus(timeout))) {
                            future.complete(false);
                        }
                    }
                },
                0,
                1,
                TimeUnit.SECONDS);

        return future.join();
    }

    /**
     * Returns the gRPC endpoint for the block stream service.
     *
     * @return the gRPC endpoint
     */
    public String getGrpcEndPoint() {
        return GRPC_END_POINT;
    }

    public void openBlock(long blockNumber) {
        blockStreamStateManager.openBlock(blockNumber);
        synchronized (connectionLock) {
            List<BlockNodeConnection> connections = new ArrayList<>(activeConnections.values());
            for (BlockNodeConnection connection : connections) {
                connection.getIsActiveLock().lock();
                try {
                    if (connection.isActive()) {
                        if (connection.getCurrentBlockNumber() == -1) {
                            connection.setCurrentBlockNumber(blockNumber);
                        }
                        connection.notifyNewBlockAvailable();
                    }
                } finally {
                    connection.getIsActiveLock().unlock();
                }
            }
        }
    }

    public void notifyConnectionsOfNewRequest() {
        synchronized (connectionLock) {
            List<BlockNodeConnection> connections = new ArrayList<>(activeConnections.values());
            for (BlockNodeConnection connection : connections) {
                connection.getIsActiveLock().lock();
                try {
                    if (connection.isActive()) {
                        connection.notifyNewRequestAvailable();
                    }
                } finally {
                    connection.getIsActiveLock().unlock();
                }
            }
        }
    }
}
