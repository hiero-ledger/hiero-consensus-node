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
import io.helidon.common.tls.Tls;
import io.helidon.webclient.grpc.GrpcClient;
import io.helidon.webclient.grpc.GrpcClientMethodDescriptor;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import io.helidon.webclient.grpc.GrpcServiceClient;
import io.helidon.webclient.grpc.GrpcServiceDescriptor;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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

    private final Map<BlockNodeConfig, BlockNodeConnection> activeConnections;
    private BlockNodeConfigExtractor blockNodeConfigurations;

    private final Object connectionLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService streamingExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService retryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService connectionExecutor;
    private final int maxSimultaneousConnections;

    /**
     * Creates a new BlockNodeConnectionManager with the given configuration from disk.
     * @param configProvider the configuration provider
     */
    public BlockNodeConnectionManager(@NonNull final ConfigProvider configProvider) {
        requireNonNull(configProvider);
        this.activeConnections = new ConcurrentHashMap<>();

        final var blockStreamConfig = configProvider.getConfiguration().getConfigData(BlockStreamConfig.class);
        if (!blockStreamConfig.streamToBlockNodes()) {
            maxSimultaneousConnections = 0;
            connectionExecutor = Executors.newScheduledThreadPool(1);

            return;
        }
        final var blockNodeConfig = configProvider.getConfiguration().getConfigData(BlockNodeConnectionConfig.class);
        this.blockNodeConfigurations = new BlockNodeConfigExtractor(blockNodeConfig.blockNodeConnectionFileDir());
        this.maxSimultaneousConnections = blockNodeConfigurations.getMaxNumberOfSimultaneousConnections();
        this.connectionExecutor = Executors.newScheduledThreadPool(maxSimultaneousConnections);
    }

    /**
     * Attempts to establish connections to block nodes based on priority and configuration.
     */
    private void establishConnections() {
        logger.info("Establishing connections to block nodes");
        List<BlockNodeConfig> availableNodes = blockNodeConfigurations.getAllNodes();

        Map<Integer, List<BlockNodeConfig>> priorityGroups =
                availableNodes.stream().collect(Collectors.groupingBy(BlockNodeConfig::priority));

        List<Integer> sortedPriorities = new ArrayList<>(priorityGroups.keySet());
        sortedPriorities.sort(Integer::compare);

        // Ensure priority-based order of insertion
        List<BlockNodeConfig> selectedNodes = new ArrayList<>();
        for (Integer priority : sortedPriorities) {
            List<BlockNodeConfig> nodesInGroup = new ArrayList<>(priorityGroups.get(priority));
            Collections.shuffle(nodesInGroup);
            for (BlockNodeConfig node : nodesInGroup) {
                if (selectedNodes.size() >= maxSimultaneousConnections) {
                    break;
                }
                selectedNodes.add(node);
            }
            if (selectedNodes.size() >= maxSimultaneousConnections) {
                break;
            }
        }
        selectedNodes.forEach(this::connectToNode);
    }

    void connectToNode(@NonNull BlockNodeConfig node) {
        logger.info("Connecting to block node {}:{}", node.address(), node.port());
        try {
            GrpcClient client = GrpcClient.builder()
                    .tls(Tls.builder().enabled(false).build())
                    .baseUri(new URI("http://" + node.address() + ":" + node.port()))
                    .protocolConfig(GrpcClientProtocolConfig.builder()
                            .abortPollTimeExpired(false)
                            .build())
                    .keepAlive(true)
                    .build();

            GrpcServiceClient grpcServiceClient = client.serviceClient(GrpcServiceDescriptor.builder()
                    .serviceName(BlockStreamServiceGrpc.SERVICE_NAME)
                    .putMethod(
                            GRPC_END_POINT,
                            GrpcClientMethodDescriptor.bidirectional(
                                            BlockStreamServiceGrpc.SERVICE_NAME, GRPC_END_POINT)
                                    .requestType(PublishStreamRequest.class)
                                    .responseType(PublishStreamResponse.class)
                                    .build())
                    .build());

            BlockNodeConnection connection = new BlockNodeConnection(node, grpcServiceClient, this);
            connection.establishStream();
            synchronized (connectionLock) {
                activeConnections.put(node, connection);
            }
            logger.info("Successfully connected to block node {}:{}", node.address(), node.port());
        } catch (URISyntaxException | RuntimeException e) {
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
            List<BlockItem> protocBlockItems = new ArrayList<>();
            batch.forEach(batchItem -> {
                try {
                    protocBlockItems.add(BlockItem.parseFrom(batchItem.toByteArray()));
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
                        "Successfully streamed block {} to {}:{}",
                        blockNumber,
                        connectionNodeConfig.address(),
                        connectionNodeConfig.port());
            } catch (Exception e) {
                logger.error(
                        "Failed to stream block {} to {}:{}",
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
                logger.info("Retrying in {} ms", delay.toMillis());
                Thread.sleep(delay.toMillis());
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
        try {
            if (!connectionExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.error("Failed to shut down connection executor within timeout");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        synchronized (connectionLock) {
            activeConnections.values().forEach(BlockNodeConnection::close);
            activeConnections.clear();
        }
    }

    /**
     * Waits for at least one active connection to be established, with timeout.
     * @param timeout maximum time to wait
     * @return true if at least one connection was established, false if timeout occurred
     */
    public boolean waitForConnections(Duration timeout) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        establishConnections();

        Instant deadline = Instant.now().plus(timeout);

        connectionExecutor.scheduleAtFixedRate(
                () -> {
                    if (activeConnections.size() >= maxSimultaneousConnections) {
                        future.complete(true);
                    } else if (Instant.now().isAfter(deadline)) {
                        future.complete(false);
                    }
                },
                0,
                1,
                TimeUnit.SECONDS);

        return future.join();
    }

    /**
     * @return the gRPC endpoint for publish block stream
     */
    public String getGrpcEndPoint() {
        return GRPC_END_POINT;
    }
}
