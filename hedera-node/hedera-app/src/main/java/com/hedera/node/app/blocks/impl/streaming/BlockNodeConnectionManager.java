// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.grpc.GrpcClient;
import io.helidon.webclient.grpc.GrpcClientMethodDescriptor;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import io.helidon.webclient.grpc.GrpcServiceClient;
import io.helidon.webclient.grpc.GrpcServiceDescriptor;
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
    private static final long RETRY_BACKOFF_MULTIPLIER = 2;
    private static final String GRPC_END_POINT =
            BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();
    // Maximum retry delay to prevent excessively long waits
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(1);

    // Add a random number generator for jitter
    private final Random random = new Random();

    private final Map<BlockNodeConfig, BlockNodeConnection> activeConnections;
    private final Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection;

    private final BlockNodeConfigExtractor blockNodeConfigurations;
    private final BlockStreamStateManager blockStreamStateManager;

    private final Object connectionLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().factory()); // Use virtual threads for scheduler too
    private final ExecutorService streamingExecutor = Executors.newSingleThreadExecutor();
    private final BlockStreamMetrics blockStreamMetrics;

    /**
     * Creates a new BlockNodeConnectionManager with the given configuration from disk.
     * @param blockNodeConfigExtractor the block node configuration extractor
     * @param blockStreamStateManager the block stream state manager
     */
    public BlockNodeConnectionManager(
            @NonNull final BlockNodeConfigExtractor blockNodeConfigExtractor,
            @NonNull final BlockStreamStateManager blockStreamStateManager,
            @NonNull final BlockStreamMetrics blockStreamMetrics) {
        this.blockNodeConfigurations =
                requireNonNull(blockNodeConfigExtractor, "blockNodeConfigExtractor must not be null");
        this.blockStreamStateManager =
                requireNonNull(blockStreamStateManager, "blockStreamStateManager must not be null");
        this.activeConnections = new ConcurrentHashMap<>();
        this.lastVerifiedBlockPerConnection = new ConcurrentHashMap<>();
        this.blockStreamMetrics = blockStreamMetrics;
    }

    /**
     * Attempts to establish connections to block nodes based on priority and configuration.
     */
    private void establishConnections() {
        logger.debug("Establishing connections to block nodes");

        List<BlockNodeConfig> availableNodes = blockNodeConfigurations.getAllNodes().stream()
                .filter(node -> !activeConnections.containsKey(node))
                .toList();

        availableNodes.forEach(this::connectToNode);
    }

    private GrpcServiceClient createNewGrpcClient(@NonNull BlockNodeConfig node) {
        final GrpcClient client = GrpcClient.builder()
                .tls(Tls.builder().enabled(false).build())
                .baseUri("http://" + node.address() + ":" + node.port())
                .protocolConfig(GrpcClientProtocolConfig.builder()
                        .abortPollTimeExpired(false)
                        .pollWaitTime(Duration.ofSeconds(30))
                        .build())
                .keepAlive(true)
                .build();

        return client.serviceClient(GrpcServiceDescriptor.builder()
                .serviceName(BlockStreamServiceGrpc.SERVICE_NAME)
                .putMethod(
                        GRPC_END_POINT,
                        GrpcClientMethodDescriptor.bidirectional(BlockStreamServiceGrpc.SERVICE_NAME, GRPC_END_POINT)
                                .requestType(PublishStreamRequest.class)
                                .responseType(PublishStreamResponse.class)
                                .marshallerSupplier(new RequestResponseMarshaller.Supplier())
                                .build())
                .build());
    }

    private void connectToNode(@NonNull BlockNodeConfig node) {
        synchronized (connectionLock) {
            try {
                final GrpcServiceClient grpcClient = createNewGrpcClient(node);
                final BlockNodeConnection connection =
                        new BlockNodeConnection(node, this, blockStreamStateManager, grpcClient, blockStreamMetrics);
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

    /**
     * Handles connection errors from a BlockNodeConnection by removing the failed connection
     * and initiating the reconnection process.
     *
     * @param node the node configuration for the failed connection
     */
    public synchronized void disconnectFromNode(@NonNull BlockNodeConfig node) {
        synchronized (connectionLock) {
            final BlockNodeConnection connection = activeConnections.remove(node);
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
     * Schedules a reconnect for the given Block Node connection.
     *
     * @param connection the connection to schedule a reconnect for
     */
    public void scheduleReconnect(@NonNull final BlockNodeConnection connection) {
        requireNonNull(connection);

        // No longer need retryExecutor.execute(() -> { ... });
        // Directly schedule the first attempt using the scheduler
        synchronized (connectionLock) {
            final var blockNodeConfig = connection.getNodeConfig();
            // Ensure we only schedule a reconnect if the connection isn't already active
            // or being reconnected by another thread.
            if (!activeConnections.containsKey(blockNodeConfig)) {
                logger.debug(
                        "Scheduling initial reconnect attempt for block node {}:{}",
                        blockNodeConfig.address(),
                        blockNodeConfig.port());
                retry(() -> establishStreamForNode(connection), INITIAL_RETRY_DELAY, 1); // Start with attempt 1
            } else {
                logger.debug(
                        "Reconnect already in progress or connection active for block node {}:{}",
                        blockNodeConfig.address(),
                        blockNodeConfig.port());
            }
        }
    }

    /**
     * Helper method to establish stream for a specific connection, used by retry logic.
     * Adds the connection to activeConnections upon success.
     * @param connection The connection to establish the stream for.
     * @return true if successful, false otherwise.
     */
    private boolean establishStreamForNode(@NonNull final BlockNodeConnection connection) {
        final var blockNodeConfig = connection.getNodeConfig();
        try {
            connection.establishStream(); // Attempt to establish the stream
            connection.getIsActiveLock().lock();
            try {
                if (connection.isActive()) {
                    // Check again if another thread reconnected in the meantime
                    synchronized (connectionLock) {
                        if (!activeConnections.containsKey(blockNodeConfig)) {
                            activeConnections.put(blockNodeConfig, connection);
                            logger.info(
                                    "Successfully reconnected to block node {}:{}",
                                    blockNodeConfig.address(),
                                    blockNodeConfig.port());
                            return true; // Success
                        } else {
                            logger.warn(
                                    "Connection to {}:{} established, but another connection was already active. Closing this one.",
                                    blockNodeConfig.address(),
                                    blockNodeConfig.port());
                            connection.close(); // Close the redundant connection
                            return true; // Still counts as "success" for retry logic, as *a* connection exists
                        }
                    }
                } else {
                    logger.warn(
                            "Stream establishment attempt for {}:{} did not result in an active connection.",
                            blockNodeConfig.address(),
                            blockNodeConfig.port());
                    return false; // Failure
                }
            } finally {
                connection.getIsActiveLock().unlock();
            }
        } catch (Exception e) {
            logger.warn(
                    "Failed reconnect attempt to block node {}:{}: {}",
                    blockNodeConfig.address(),
                    blockNodeConfig.port(),
                    e.getMessage()); // Log only message for retry attempts
            return false; // Failure
        }
    }

    /**
     * Retries the given action with exponential backoff using the scheduler.
     *
     * @param action the action returning true on success, false on failure (to allow retry)
     * @param currentDelay the delay to use for this attempt
     * @param attempt the current attempt number
     */
    private void retry(
            @NonNull final Supplier<Boolean> action, @NonNull final Duration currentDelay, final int attempt) {
        requireNonNull(action);
        requireNonNull(currentDelay);

        // Apply jitter: use a random value between 50-100% of the calculated delay
        final long baseDelayMs = Math.min(currentDelay.toMillis(), MAX_RETRY_DELAY.toMillis());
        final long jitteredDelayMs = baseDelayMs / 2 + random.nextLong(baseDelayMs / 2 + 1);

        logger.debug("Scheduling retry attempt {} in {} ms", attempt, jitteredDelayMs);

        scheduler.schedule(
                () -> {
                    boolean success = false;
                    try {
                        success = action.get();
                    } catch (Exception e) {
                        logger.error("Exception during scheduled retry action (attempt {})", attempt, e);
                        // Treat exceptions during the action itself as failure
                        success = false;
                    }

                    if (!success) {
                        Duration nextDelay = currentDelay.multipliedBy(RETRY_BACKOFF_MULTIPLIER);
                        // Ensure nextDelay doesn't exceed MAX_RETRY_DELAY before passing to next retry
                        if (nextDelay.compareTo(MAX_RETRY_DELAY) > 0) {
                            nextDelay = MAX_RETRY_DELAY;
                        }
                        logger.debug("Retry attempt {} failed. Scheduling next attempt.", attempt);
                        retry(action, nextDelay, attempt + 1); // Schedule the next attempt
                    } else {
                        logger.debug("Retry attempt {} successful.", attempt);
                    }
                },
                jitteredDelayMs,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Shuts down the connection manager, closing all active connections.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            // Wait for scheduler tasks to complete or timeout
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Scheduler did not terminate within 10 seconds. Forcing shutdown.");
                scheduler.shutdownNow(); // Force shutdown if needed
            } else {
                logger.debug("Successfully shut down scheduler.");
            }

            boolean awaitTermination = streamingExecutor.awaitTermination(10, TimeUnit.SECONDS);
            if (!awaitTermination) {
                logger.error("Failed to shut down streaming executor within 10 seconds");
            } else {
                logger.debug("Successfully shut down streaming executor");
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

        final var deadline = Instant.now().plus(timeout);
        scheduler.scheduleAtFixedRate(
                () -> {
                    synchronized (connectionLock) {
                        if (!activeConnections.isEmpty()) {
                            future.complete(true);
                        } else if (Instant.now().isAfter(deadline)) {
                            future.complete(false);
                        }
                    }
                },
                0,
                1,
                TimeUnit.SECONDS);

        return future.join();
    }

    public void openBlock(long blockNumber) {
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

    /**
     * Returns the gRPC endpoint for the block stream service.
     * @return the gRPC endpoint
     */
    public String getGrpcEndPoint() {
        return GRPC_END_POINT;
    }

    /**
     * @param blockNodeConfig the configuration for the block node
     * @param blockNumber the block number of the last verified block
     */
    public void updateLastVerifiedBlock(
            @NonNull final BlockNodeConfig blockNodeConfig, @Nullable final Long blockNumber) {
        requireNonNull(blockNodeConfig);

        final Long latestBlock = getLastVerifiedBlock(blockNodeConfig);
        if (blockNumber != null && blockNumber > latestBlock) {
            lastVerifiedBlockPerConnection.put(blockNodeConfig, blockNumber);
        } else {
            logger.warn(
                    "Attempted to update connection {} with invalid block number {} (highest {})",
                    blockNodeConfig,
                    blockNumber,
                    latestBlock);
        }
    }

    /**
     * @param blockNodeConfig the configuration for the block node
     * @return the last verified block number by the given block node.
     */
    public Long getLastVerifiedBlock(@NonNull final BlockNodeConfig blockNodeConfig) {
        requireNonNull(blockNodeConfig);
        return lastVerifiedBlockPerConnection.computeIfAbsent(blockNodeConfig, key -> -1L);
    }

    /**
     * @param blockNumber the block number to check for acknowledgements
     * @return whether the block has been acknowledged by any connection.
     */
    public boolean isBlockAlreadyAcknowledged(@NonNull final Long blockNumber) {
        requireNonNull(blockNumber);
        return lastVerifiedBlockPerConnection.values().stream()
                .anyMatch(verifiedBlock -> verifiedBlock.equals(blockNumber));
    }
}
