// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

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
import org.hiero.block.api.protoc.BlockStreamPublishServiceGrpc;
import org.hiero.block.api.protoc.PublishStreamRequest;
import org.hiero.block.api.protoc.PublishStreamResponse;

/**
 * Manages connections to block nodes, connection lifecycle and node selection.
 * It is also responsible for retrying with exponential backoff if a connection fails.
 */
public class BlockNodeConnectionManager {
    public static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Logger logger = LogManager.getLogger(BlockNodeConnectionManager.class);
    private static final long RETRY_BACKOFF_MULTIPLIER = 2;
    private static final String GRPC_END_POINT =
            BlockStreamPublishServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();

    // Add a random number generator for jitter
    private final Random random = new Random();

    private final Map<BlockNodeConfig, BlockNodeConnection> activeConnections;
    private final Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection;

    private final BlockNodeConfigExtractor blockNodeConfigurations;
    private final BlockStreamStateManager blockStreamStateManager;

    private final Object connectionLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService streamingExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService retryExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Creates a new BlockNodeConnectionManager with the given configuration from disk.
     * @param blockNodeConfigExtractor the block node configuration extractor
     * @param blockStreamStateManager the block stream state manager
     */
    public BlockNodeConnectionManager(
            @NonNull final BlockNodeConfigExtractor blockNodeConfigExtractor,
            @NonNull final BlockStreamStateManager blockStreamStateManager) {
        this.blockNodeConfigurations =
                requireNonNull(blockNodeConfigExtractor, "blockNodeConfigExtractor must not be null");
        this.blockStreamStateManager =
                requireNonNull(blockStreamStateManager, "blockStreamStateManager must not be null");
        this.activeConnections = new ConcurrentHashMap<>();
        this.lastVerifiedBlockPerConnection = new ConcurrentHashMap<>();
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
                .serviceName(BlockStreamPublishServiceGrpc.SERVICE_NAME)
                .putMethod(
                        GRPC_END_POINT,
                        GrpcClientMethodDescriptor.bidirectional(
                                        BlockStreamPublishServiceGrpc.SERVICE_NAME, GRPC_END_POINT)
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
                        new BlockNodeConnection(node, this, blockStreamStateManager, grpcClient);
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

        retryExecutor.execute(() -> {
            synchronized (connectionLock) {
                final var blockNodeConfig = connection.getNodeConfig();
                try {
                    if (!activeConnections.containsKey(blockNodeConfig)) {
                        connection.getIsActiveLock().lock();
                        try {
                            if (!connection.isActive()) {
                                activeConnections.put(blockNodeConfig, connection);
                                retry(connection::establishStream, INITIAL_RETRY_DELAY);
                            }
                        } finally {
                            connection.getIsActiveLock().unlock();
                        }
                    }
                } catch (Exception e) {
                    logger.error(
                            "Failed to re-establish stream to block node {}:{}: {}",
                            blockNodeConfig.address(),
                            blockNodeConfig.port(),
                            e);
                    activeConnections.remove(blockNodeConfig);
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
                logger.debug("Retrying in {} ms", jitteredDelayMs);
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
