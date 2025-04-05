// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
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
    public static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Logger logger = LogManager.getLogger(BlockNodeConnectionManager.class);
    private static final long RETRY_BACKOFF_MULTIPLIER = 2;
    private static final String GRPC_END_POINT =
            BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();

    // Add a random number generator for retry jitter
    private final Random random = new Random();

    private final Map<BlockNodeConfig, BlockNodeConnection> connectionsInRetry;
    private final BlockNodeConfigExtractor blockNodeConfigurations;
    private final BlockStreamStateManager blockStreamStateManager;

    private final Object connectionLock = new Object();
    private final ExecutorService retryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService connectionExecutor = Executors.newScheduledThreadPool(1);
    private BlockNodeConnection primary;
    private BlockNodeConnection secondary;

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

        this.connectionsInRetry = new ConcurrentHashMap<>();
    }

    /**
     * @return the gRPC endpoint for publish block stream
     */
    public String getGrpcEndPoint() {
        return GRPC_END_POINT;
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

    /**
     * Handles connection errors from a BlockNodeConnection by removing the failed connection
     *
     * @param connection the connection that received the error
     */
    public void handleConnectionError(@NonNull final BlockNodeConnection connection) {
        synchronized (connectionLock) {
            // If available, make the secondary connection the primary connection
            if (Objects.equals(connection, primary) && !secondaryActive()) {
                primary = secondary;
            }
            connectionsInRetry.putIfAbsent(connection.getNodeConfig(), connection);
        }
        scheduleReconnect(connection);
    }

    /**
     * Schedules a reconnect for the given Block Node connection.
     *
     * @param connection the connection to schedule a reconnect for
     */
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
     * Waits for at least one connection to be established.
     *
     * @param timeout the maximum time to wait
     * @return true if at least one connection was established, false if the timeout elapsed before any connections were established
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

    public void openBlock(long blockNumber) {
        synchronized (connectionLock) {
            // Identify the currently-active connection
            final BlockNodeConnection connection = getActiveConnection().orElse(null);
            if (connection == null) {
                logger.warn("No active connections available for streaming block {}", blockNumber);
                return;
            }

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

    public void notifyConnectionsOfNewRequest() {
        synchronized (connectionLock) {
            final BlockNodeConnection connection = getActiveConnection().orElse(null);
            if (connection == null) {
                logger.warn("No active connections available for streaming new request");
                return;
            }

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
            final GrpcServiceClient grpcClient = createNewGrpcClient(node);
            BlockNodeConnection connection = new BlockNodeConnection(node, this, blockStreamStateManager, grpcClient);
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

        private NoOpConnection() {}

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
