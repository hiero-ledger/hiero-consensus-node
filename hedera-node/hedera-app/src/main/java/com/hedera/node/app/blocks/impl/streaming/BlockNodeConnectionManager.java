// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.protoc.BlockItemSet;
import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
import com.hedera.hapi.block.stream.protoc.BlockItem;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

    // Add a random number generator for retry jitter
    private final Random random = new Random();

    private final Map<BlockNodeConfig, BlockNodeConnection> connectionsInRetry;
    private BlockNodeConfigExtractor blockNodeConfigurations;

    private final Object connectionLock = new Object();
    private final ExecutorService streamingExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService retryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService connectionExecutor;
    private BlockNodeConnection primary;
    private BlockNodeConnection secondary;
    private final Map<BlockNodeConfig, Long> latestBlocks;

    /**
     * Creates a new BlockNodeConnectionManager with the given configuration from disk.
     * @param configProvider the configuration provider
     */
    public BlockNodeConnectionManager(@NonNull final ConfigProvider configProvider) {
        requireNonNull(configProvider);

        final var blockStreamConfig = configProvider.getConfiguration().getConfigData(BlockStreamConfig.class);
        if (blockStreamConfig.streamToBlockNodes()) {
            final var blockNodeConfig =
                    configProvider.getConfiguration().getConfigData(BlockNodeConnectionConfig.class);
            this.blockNodeConfigurations = new BlockNodeConfigExtractor(blockNodeConfig.blockNodeConnectionFileDir());
            this.latestBlocks = new HashMap<>();
            // (FUTURE) Load latest blocks less naively
            this.blockNodeConfigurations.getAllNodes().forEach(node -> latestBlocks.put(node, -1L));

            this.connectionsInRetry = new ConcurrentHashMap<>();
            this.connectionExecutor = Executors.newScheduledThreadPool(1);
        } else {
            // Block node streaming is disabled, so no connections are needed
            this.latestBlocks = Collections.emptyMap();
            this.connectionsInRetry = Collections.emptyMap();
            this.connectionExecutor = null;
        }
    }

    /**
     * @return the gRPC endpoint for publish block stream
     */
    public String getGrpcEndPoint() {
        return GRPC_END_POINT;
    }

    public void handleEndOfStreamSuccess(@NonNull final BlockNodeConnection connection) {
        handleEndOfStreamSuccess(connection, null);
    }

    public void handleEndOfStreamSuccess(
            @NonNull final BlockNodeConnection connection, @Nullable final Long blockNumber) {
        requireNonNull(connection);
        updateLatestBlock(connection, blockNumber);
    }

    /**
     * Initiates the streaming of a block to all active connections.
     *
     * @param block the block to be streamed
     */
    public void startStreamingBlock(@NonNull final BlockState block) {
        streamingExecutor.execute(() -> streamBlock(block));
    }

    /**
     * Handles connection errors from a BlockNodeConnection by removing the failed connection
     *
     * @param connection the connection that received the error
     * @param thrown the error that occurred
     */
    public void handleConnectionError(
            @NonNull final BlockNodeConnection connection,
            // This isn't used yet, but will be once the code for responding to specific error codes is introduced
            @SuppressWarnings("unused") @NonNull final Throwable thrown) {
        synchronized (connectionLock) {
            // If available, make the secondary connection the primary connection
            if (Objects.equals(connection, primary) && !secondaryActive()) {
                primary = secondary;
            }
            connectionsInRetry.putIfAbsent(connection.getNodeConfig(), connection);
        }
        scheduleReconnect(connection);
    }

    public void handleBlockAck(
            @NonNull final BlockNodeConnection connection,
            @NonNull final PublishStreamResponse.BlockAcknowledgement blockAck) {
        final var blockNum = blockAck.getBlockNumber();
        logger.info(
                "Block {} acknowledged by block node {}",
                blockAck.getBlockNumber(),
                blockNodeName(connection.getNodeConfig()));
        if (blockAck.getBlockAlreadyExists()) {
            logger.warn(
                    "Block {} already exists on block node {}", blockNum, blockNodeName(connection.getNodeConfig()));
        } else {
            logger.info(
                    "Block {} successfully processed by block node {}",
                    blockAck.getBlockNumber(),
                    blockNodeName(connection.getNodeConfig()));
        }

        // Regardless of whether the block already exists, we want to update the latest block number
        updateLatestBlock(connection, blockNum);
    }

    public void handleStreamError(
            @NonNull final BlockNodeConnection connection, PublishStreamResponse.EndOfStream endOfStream) {
        logger.error(
                "Stream error for block node {} (status {})",
                blockNodeName(connection.getNodeConfig()),
                endOfStream.getStatus());

        if (Objects.equals(primary, connection)) {
            primary = null;
        } else if (Objects.equals(secondary, connection)) {
            secondary = null;
        }

        connectionsInRetry.put(connection.getNodeConfig(), connection);

        // Attempt to reconnect
        scheduleReconnect(connection);
    }

    public void scheduleReconnect(@NonNull final BlockNodeConnection connection) {
        requireNonNull(connection);

        // Avoid duplicate retry attempts
        synchronized (connectionLock) {
            if (connectionsInRetry.containsKey(connection.getNodeConfig())) {
                logger.info("Skipping reconnect, already in retry: {}", blockNodeName(connection.getNodeConfig()));
                return;
            }
        }

        retryExecutor.execute(() -> {
            try {
                retry(
                        () -> {
                            connection.establishStream();
                            synchronized (connectionLock) {
                                connectionsInRetry.remove(connection.getNodeConfig());
                            }
                            return true;
                        },
                        INITIAL_RETRY_DELAY);
            } catch (Exception e) {
                final var node = connection.getNodeConfig();
                logger.error("Failed to re-establish stream to block node {}", blockNodeName(node), e);
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
        connectionExecutor.shutdown();
        retryExecutor.shutdown();
        try {
            if (!connectionExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.error("Failed to shut down connection executor within timeout");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            synchronized (connectionLock) {
                primary.close();
                secondary.close();
                connectionsInRetry.clear();
            }
        }
    }

    /**
     * Waits for at least one active connection to be established, with timeout.
     * @param timeout maximum time to wait
     * @return true if at least one connection was established, false if timeout occurred
     */
    public boolean waitForConnection(Duration timeout) {
        final Instant deadline = Instant.now().plus(timeout);
        establishConnection();

        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final ScheduledFuture<?> scheduledTask = connectionExecutor.scheduleAtFixedRate(
                () -> {
                    if (!primaryActive() && !secondaryActive()) {
                        // Since neither connection is active, we can try to establish a new connection
                        future.complete(true); // Ensure future completed
                    } else if (Instant.now().isAfter(deadline)) {
                        future.complete(false); // Handle timeout
                    }
                    // Otherwise we just keep waiting, don't return anything
                },
                0,
                100,
                TimeUnit.MILLISECONDS);

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); // Avoid indefinite blocking
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            scheduledTask.cancel(true);
        }
    }

    static String blockNodeName(@Nullable final BlockNodeConfig node) {
        return node != null ? node.address() + ":" + node.port() : "null";
    }

    /**
     * Attempts to establish a connection to a block node based on priority.
     */
    private void establishConnection() {
        logger.info("Establishing connection to primary block node");
        List<BlockNodeConfig> availableNodes = blockNodeConfigurations.getAllNodes();

        final Map<Integer, List<BlockNodeConfig>> priorityGroups =
                availableNodes.stream().collect(Collectors.groupingBy(BlockNodeConfig::priority));
        final List<Integer> sortedPriorities = new ArrayList<>(priorityGroups.keySet());
        sortedPriorities.sort(Integer::compare);

        // Find the current lowest priority of active connections
        final int currentMinPriority = getCurrentMinPriority();

        BlockNodeConfig selectedNode = null;
        for (Integer priority : sortedPriorities) {
            // Skip over any nodes that have less priority than the current minimum
            if (priority >= currentMinPriority) continue;

            // Filter nodes not in retry, and select one randomly
            final List<BlockNodeConfig> nextPriorityGroup = priorityGroups.get(priority).stream()
                    .filter(node -> {
                        synchronized (connectionLock) {
                            return !isRetrying(node);
                        }
                    })
                    .toList();
            final List<BlockNodeConfig> availableNodesInGroup = new ArrayList<>(nextPriorityGroup);
            if (!availableNodesInGroup.isEmpty()) {
                final var randomIndex = IntStream.range(0, availableNodesInGroup.size())
                        .findAny()
                        .orElseThrow();
                selectedNode = availableNodesInGroup.get(randomIndex);
                break;
            }
        }
        if (selectedNode == null) {
            throw new IllegalStateException(
                    "No available block node found for connection. Check configuration and network status.");
        }

        connectToNode(selectedNode);
        if (!primaryActive()) {
            synchronized (connectionLock) {
                connectionsInRetry.put(selectedNode, NoOpConnection.INSTANCE);
            }

            // Now that we have put the current connection in retry, we can try again to establish a new connection. If
            // there are no successful connections made, eventually this recursive call will throw an exception
            establishConnection();
        }
    }

    private void rememberConnection(@NonNull BlockNodeConnection connection) {
        if (!primaryActive()) {
            primary = connection;
        } else if (!secondaryActive()) {
            secondary = connection;
        } else {
            logger.warn("Both primary and secondary connections are already established");
        }
    }

    private void connectToNode(@NonNull BlockNodeConfig node) {
        logger.info("Connecting to block node {}", blockNodeName(node));
        try {
            BlockNodeConnection connection = new BlockNodeConnection(node, this);
            connection.establishStream();
            synchronized (connectionLock) {
                rememberConnection(connection);
                connectionsInRetry.remove(node);
            }
            logger.info("Successfully connected to block node {}", blockNodeName(node));
        } catch (Exception e) {
            logger.error("Failed to connect to block node {}", blockNodeName(node), e);
        }
    }

    private Long getLatestBlock(@NonNull final BlockNodeConnection connection) {
        return latestBlocks.get(connection.getNodeConfig());
    }

    private void updateLatestBlock(@NonNull final BlockNodeConnection connection, @Nullable final Long blockNumber) {
        requireNonNull(connection);

        final Long latestBlock = getLatestBlock(connection);
        if (blockNumber != null && blockNumber > latestBlock) {
            latestBlocks.put(connection.getNodeConfig(), blockNumber);
        } else {
            logger.warn(
                    "Attempted to update connection {} with invalid block number {} (highest {})",
                    blockNodeName(connection.getNodeConfig()),
                    blockNumber,
                    latestBlock);
        }
    }

    private Optional<BlockNodeConnection> getActiveConnection() {
        synchronized (connectionLock) {
            if (primaryActive()) {
                return Optional.of(primary);
            } else if (secondaryActive()) {
                return Optional.of(secondary);
            }
        }
        return Optional.empty();
    }

    /**
     * Streams the given block to the active connection.
     *
     * @param block the block to stream
     */
    private void streamBlock(@NonNull BlockState block) {
        final long blockNumber = block.blockNumber();

        // Identify the currently-active connection
        final BlockNodeConnection conn = getActiveConnection().orElse(null);
        if (conn == null) {
            logger.warn("No active connections available for streaming block {}", blockNumber);
            return;
        }

        logger.info("Beginning stream of block {} to {}", blockNumber, blockNodeName(conn.getNodeConfig()));
        final int blockItemBatchSize = blockNodeConfigurations.getBlockItemBatchSize();
        PublishStreamRequest request;
        for (int i = 0; i < block.itemBytes().size(); i += blockItemBatchSize) {
            final int end = Math.min(i + blockItemBatchSize, block.itemBytes().size());
            final List<Bytes> batch = block.itemBytes().subList(i, end);
            final List<BlockItem> protocBlockItems = new ArrayList<>();
            batch.forEach(batchItem -> {
                try {
                    final BlockItem itemAsProtoC = BlockItem.parseFrom(batchItem.toByteArray());
                    protocBlockItems.add(itemAsProtoC);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            // Create BlockItemSet by adding all items at once
            final BlockItemSet itemSet =
                    BlockItemSet.newBuilder().addAllBlockItems(protocBlockItems).build();
            request = PublishStreamRequest.newBuilder().setBlockItems(itemSet).build();

            // Stream the chunk
            final var connectionNodeConfig = conn.getNodeConfig();
            try {
                conn.sendRequest(request);
            } catch (Exception e) {
                logger.error("Failed to stream batch (byte {} – byte {}) to {}", i, end, connectionNodeConfig, e);
            }

            // (FUTURE) Add sent batches/items to a cache
        }

        logger.info("Successfully streamed block {} to {}", blockNumber, blockNodeName(conn.getNodeConfig()));
    }

    private boolean isRetrying(BlockNodeConnection connection) {
        return isRetrying(connection.getNodeConfig());
    }

    private boolean isRetrying(BlockNodeConfig config) {
        return connectionsInRetry.containsKey(config);
    }

    private int getCurrentMinPriority() {
        final var activeConn = getActiveConnection();
        return activeConn
                .map(ac -> ac.getNodeConfig().priority())
                // If no active connection, return max priority
                .orElse(Integer.MAX_VALUE);
    }

    private boolean primaryActive() {
        return connectionActive(primary);
    }

    private boolean secondaryActive() {
        return connectionActive(secondary);
    }

    private boolean connectionActive(BlockNodeConnection connection) {
        return connection != null && !isRetrying(connection) && connection.isActive();
    }

    // This class exists solely to avoid checking for null every time we reference a connection in connectionsInRetry
    private static class NoOpConnection extends BlockNodeConnection {
        static final NoOpConnection INSTANCE;

        static {
            INSTANCE = new NoOpConnection();
        }

        private NoOpConnection() {
            super(null, null);
        }

        @Override
        public void establishStream() {
            // No-op
        }

        @Override
        public void onNext(PublishStreamResponse response) {
            // No-op
        }

        @Override
        public void onError(Throwable throwable) {
            // No-op
        }

        @Override
        public void onCompleted() {
            // No-op
        }

        @Override
        public void sendRequest(@NonNull final PublishStreamRequest request) {
            // No-op
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public void close() {
            // No-op
        }

        @Override
        public BlockNodeConfig getNodeConfig() {
            return BlockNodeConfig.DEFAULT;
        }
    }
}
