// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.protoc.BlockItemSet;
import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
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
    public static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Logger logger = LogManager.getLogger(BlockNodeConnectionManager.class);
    private static final String GRPC_END_POINT =
            BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();
    private static final long RETRY_BACKOFF_MULTIPLIER = 2;

    // Add a random number generator for jitter
    private final Random random = new Random();

    private final Map<BlockNodeConfig, BlockNodeConnection> activeConnections;
    private BlockNodeConfigExtractor blockNodeConfigurations;

    private final Object connectionLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService streamingExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService retryExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Creates a new BlockNodeConnectionManager with the given configuration from disk.
     * @param configProvider the configuration provider
     */
    public BlockNodeConnectionManager(@NonNull final ConfigProvider configProvider) {
        requireNonNull(configProvider);
        this.activeConnections = new ConcurrentHashMap<>();

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
        logger.info("Connecting to block node {}:{}", node.address(), node.port());
        try {
            BlockNodeConnection connection = new BlockNodeConnection(node, this);
            connection.establishStream();
            synchronized (connectionLock) {
                activeConnections.put(node, connection);
            }
            logger.info("Successfully connected to block node {}:{}", node.address(), node.port());
        } catch (Exception e) {
            logger.error("Failed to connect to block node {}:{}", node.address(), node.port(), e);
        }
    }

    private synchronized void disconnectFromNode(@NonNull BlockNodeConfig node) {
        synchronized (connectionLock) {
            BlockNodeConnection connection = activeConnections.remove(node);
            if (connection != null) {
                connection.close();
                logger.info("Disconnected from block node {}:{}", node.address(), node.port());
            }
        }
    }

    private void streamBlockToConnections(@NonNull BlockState block) {
        long blockNumber = block.blockNumber();
        // Get currently active connections
        List<BlockNodeConnection> connectionsToStream;
        synchronized (connectionLock) {
            connectionsToStream = activeConnections.values().stream()
                    .filter(BlockNodeConnection::isActive)
                    .toList();
        }

        if (connectionsToStream.isEmpty()) {
            logger.info("No active connections to stream block {}", blockNumber);
            return;
        }

        logger.info("Streaming block {} to {} active connections", blockNumber, connectionsToStream.size());

        // Create all batches once
        List<PublishStreamRequest> batchRequests = new ArrayList<>();
        final int blockItemBatchSize = blockNodeConfigurations.getBlockItemBatchSize();
        for (int i = 0; i < block.itemBytes().size(); i += blockItemBatchSize) {
            int end = Math.min(i + blockItemBatchSize, block.itemBytes().size());
            List<Bytes> batch = block.itemBytes().subList(i, end);
            List<com.hedera.hapi.block.stream.protoc.BlockItem> protocBlockItems = new ArrayList<>();
            batch.forEach(batchItem -> {
                try {
                    protocBlockItems.add(
                            com.hedera.hapi.block.stream.protoc.BlockItem.parseFrom(batchItem.toByteArray()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            // Create BlockItemSet by adding all items at once
            BlockItemSet itemSet =
                    BlockItemSet.newBuilder().addAllBlockItems(protocBlockItems).build();

            batchRequests.add(
                    PublishStreamRequest.newBuilder().setBlockItems(itemSet).build());
        }

        // Stream prepared batches to each connection
        for (BlockNodeConnection connection : connectionsToStream) {
            final var connectionNodeConfig = connection.getNodeConfig();
            try {
                for (PublishStreamRequest request : batchRequests) {
                    connection.sendRequest(request);
                }
                logger.info(
                        "Sent block {} to stream observer for Block Node {}:{}",
                        blockNumber,
                        connectionNodeConfig.address(),
                        connectionNodeConfig.port());
            } catch (Exception e) {
                logger.error(
                        "Failed to send block {} to stream observer for Block Node {}:{}",
                        blockNumber,
                        connectionNodeConfig.address(),
                        connectionNodeConfig.port(),
                        e);
            }
        }
    }

    /**
     * Initiates the streaming of a block to all active connections.
     *
     * @param block the block to be streamed
     */
    public void startStreamingBlock(@NonNull BlockState block) {
        streamingExecutor.execute(() -> streamBlockToConnections(block));
    }

    /**
     * Handles connection errors from a BlockNodeConnection by removing the failed connection
     * and initiating the reconnection process.
     *
     * @param node the node configuration for the failed connection
     */
    public void handleConnectionError(@NonNull BlockNodeConfig node) {
        synchronized (connectionLock) {
            activeConnections.remove(node); // Remove the failed connection
        }
    }

    public void scheduleReconnect(@NonNull final BlockNodeConnection connection) {
        requireNonNull(connection);

        retryExecutor.execute(() -> {
            try {
                activeConnections.put(connection.getNodeConfig(), connection);
                retry(connection::establishStream, INITIAL_RETRY_DELAY);
            } catch (Exception e) {
                final var node = connection.getNodeConfig();
                logger.error("Failed to re-establish stream to block node {}:{}: {}", node.address(), node.port(), e);
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
        for (BlockNodeConfig node : new ArrayList<>(activeConnections.keySet())) {
            disconnectFromNode(node);
        }
    }

    /**
     * Waits for at least one active connection to be established, with timeout.
     * @param timeout maximum time to wait
     * @return true if at least one connection was established, false if timeout occurred
     */
    public boolean waitForConnection(Duration timeout) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        establishConnections();

        scheduler.scheduleAtFixedRate(
                () -> {
                    if (!activeConnections.isEmpty()) {
                        future.complete(true);
                    } else if (Instant.now().isAfter(Instant.now().plus(timeout))) {
                        future.complete(false);
                    }
                },
                0,
                1,
                TimeUnit.SECONDS);

        return future.join();
    }

    /**
     * Returns the gRPC endpoint for the block stream service.
     * @return the gRPC endpoint
     */
    public String getGrpcEndPoint() {
        return GRPC_END_POINT;
    }
}
