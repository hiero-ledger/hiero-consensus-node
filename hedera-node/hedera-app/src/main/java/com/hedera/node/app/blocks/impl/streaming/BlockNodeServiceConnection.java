// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.pbj.runtime.grpc.GrpcException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient;
import org.hiero.block.api.ServerStatusRequest;
import org.hiero.block.api.ServerStatusResponse;

/**
 * Connection that can be used to query the Block Node Service API (e.g. to retrieve server status).
 */
public class BlockNodeServiceConnection extends AbstractBlockNodeConnection {

    private static final Logger logger = LogManager.getLogger(BlockNodeServiceConnection.class);

    /**
     * Counter used to create unique IDs for each client created.
     */
    private static final AtomicLong clientCtr = new AtomicLong(0);
    /**
     * Holder that contains the client ID and the client instance.
     *
     * @param clientId unique ID of the client
     * @param client the client instance
     */
    record ServiceClientHolder(long clientId, BlockNodeServiceClient client) {}
    /**
     * Atomic reference that contains the active client.
     */
    private final AtomicReference<ServiceClientHolder> clientRef = new AtomicReference<>();
    /**
     * Executor used to perform blocking I/O async tasks such as retrieving the block node status.
     */
    private final ExecutorService blockingIoExecutor;
    /**
     * Factory to create Block Node clients.
     */
    private final BlockNodeClientFactory clientFactory;

    /**
     * Create a new instance.
     *
     * @param configProvider the configuration provider to use
     * @param nodeConfig the block node configuration to use for this connection
     * @param blockingIoExecutor the executor service to use for executing blocking I/O tasks
     * @param clientFactory the factory to use for creating clients to the block node
     */
    public BlockNodeServiceConnection(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockNodeConfiguration nodeConfig,
            @NonNull final ExecutorService blockingIoExecutor,
            @NonNull final BlockNodeClientFactory clientFactory) {
        super(ConnectionType.SERVER_STATUS, nodeConfig, configProvider);
        this.blockingIoExecutor = requireNonNull(blockingIoExecutor, "Blocking I/O executor is required");
        this.clientFactory = requireNonNull(clientFactory, "client factory is required");
    }

    /**
     * @return the timeout out duration for async operations
     */
    private Duration operationTimeout() {
        return configProvider()
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .pipelineOperationTimeout();
    }

    @Override
    void initialize() {
        if (currentState() != ConnectionState.UNINITIALIZED) {
            logger.debug("{} Connection is already in a non-uninitialized state", this);
            return;
        }

        Future<?> future = null;

        try {
            future = blockingIoExecutor.submit(new CreateClientTask());
            future.get(operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (final Exception e) {
            logger.warn("{} Error initializing connection", this, e);

            if (future != null) {
                future.cancel(true);
            }

            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            final Throwable error;
            if (e instanceof final ExecutionException ee) {
                error = ee.getCause();
            } else {
                error = e;
            }

            throw new RuntimeException("Error initializing connection", error);
        }
    }

    /**
     * Task used to create a Block Node service client for this connection.
     */
    class CreateClientTask implements Runnable {
        @Override
        public void run() {
            final Duration timeout = configProvider()
                    .getConfiguration()
                    .getConfigData(BlockNodeConnectionConfig.class)
                    .grpcOverallTimeout();

            final long clientId = clientCtr.incrementAndGet();
            logger.debug("{} Creating new client (clientId: {})", BlockNodeServiceConnection.this, clientId);
            final BlockNodeServiceClient client = clientFactory.createServiceClient(configuration(), timeout);
            if (clientRef.compareAndSet(null, new BlockNodeServiceConnection.ServiceClientHolder(clientId, client))) {
                // unlike the streaming connection, these connections don't really have an intermediate state between
                // UNINITIALIZED and ACTIVE, so just set the state to ACTIVE
                updateConnectionState(ConnectionState.UNINITIALIZED, ConnectionState.ACTIVE);
                logger.info(
                        "{} Client initialized successfully (clientId: {})", BlockNodeServiceConnection.this, clientId);
            } else {
                logger.debug(
                        "{} Another thread has created the client and applied it to this connection; ignoring this attempt",
                        BlockNodeServiceConnection.this);
                closeSilently(new ServiceClientHolder(clientId, client));
            }
        }

        /**
         * Silently close specified client. This is used for cases where another thread has won initializing the client
         * to use for this connection, and thus we want to close the client created by the losing thread. Any errors
         * that result from closing the client will be suppressed.
         *
         * @param holder the client to close
         */
        private void closeSilently(@NonNull final ServiceClientHolder holder) {
            logger.debug("{} Silently closing client (clientId: {})", BlockNodeServiceConnection.this, holder.clientId);
            try {
                final Future<?> future = blockingIoExecutor.submit(new CloseClientTask(holder));
                future.get(operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (final Exception e) {
                logger.debug(
                        "{} Attempted to close a client (clientId: {}), but it failed; ignoring failure",
                        BlockNodeServiceConnection.this,
                        holder.clientId,
                        e);
            }
        }
    }

    /**
     * Task to close a specific client.
     */
    class CloseClientTask implements Runnable {
        /**
         * The client to close
         */
        private final ServiceClientHolder clientHolder;

        CloseClientTask(@NonNull final ServiceClientHolder clientHolder) {
            this.clientHolder = requireNonNull(clientHolder, "client is required");
        }

        @Override
        public void run() {
            logger.debug("{} Closing client (clientId: {})", BlockNodeServiceConnection.this, clientHolder.clientId);
            clientHolder.client.close();
        }
    }

    @Override
    public void close() {
        final ServiceClientHolder clientHolder = clientRef.get();

        if (clientHolder == null || currentState().isTerminal()) {
            // either close has already been called or close was called while the connection wasn't initialized
            return;
        }

        if (!clientRef.compareAndSet(clientHolder, null)) {
            logger.debug("{} Another thread has closed the connection", this);
            return;
        }

        logger.info("{} Closing connection", this);
        updateConnectionState(ConnectionState.CLOSING);

        Future<?> future = null;

        try {
            future = blockingIoExecutor.submit(new CloseClientTask(clientHolder));
            future.get(operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (final Exception e) {
            // the connection is being closed... don't propagate the exception
            logger.warn("{} Error occurred while closing connection; it will be suppressed", this, e);

            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            if (future != null) {
                future.cancel(true);
            }
        } finally {
            // regardless of outcome, mark this connection as closed
            updateConnectionState(ConnectionState.CLOSED);
        }
    }

    /**
     * Retrieves the server status of the Block Node associated with this connection. If there are any errors
     * experienced during this process, the node will be considered unreachable and as such an "unreachable" response
     * will be returned.
     *
     * @return null if this connection is not active, else the Block Node's status
     */
    public @Nullable BlockNodeStatus getBlockNodeStatus() {
        final ServiceClientHolder clientHolder = clientRef.get();

        if (clientHolder == null || currentState() != ConnectionState.ACTIVE) {
            logger.debug("{} Tried to retrieve block node status, but this connection is not active", this);
            return null;
        }

        final long startMillis = System.currentTimeMillis();
        Future<ServerStatusResponse> future = null;
        final ServerStatusResponse response;
        final long durationMillis;

        try {
            future = blockingIoExecutor.submit(new GetBlockNodeStatusTask(clientHolder.client));
            response = future.get(operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            durationMillis = System.currentTimeMillis() - startMillis;
        } catch (final Exception e) {
            final GrpcException grpcException = findGrpcException(e);
            if (grpcException != null) {
                logger.warn("{} Error retrieving block node status (grpcStatus={})", this, grpcException.status(), e);
            } else {
                logger.warn("{} Error retrieving block node status", this, e);
            }

            if (future != null) {
                future.cancel(true);
            }

            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            return BlockNodeStatus.notReachable();
        }

        logger.debug(
                "{} Received the following block node server status: lastAvailableBlock={} (latency: {}ms)",
                this,
                response.lastAvailableBlock(),
                durationMillis);

        return BlockNodeStatus.reachable(durationMillis, response.lastAvailableBlock());
    }

    /**
     * Task to get the server status.
     */
    class GetBlockNodeStatusTask implements Callable<ServerStatusResponse> {

        /**
         * The client to use
         */
        private final BlockNodeServiceClient client;

        GetBlockNodeStatusTask(@NonNull final BlockNodeServiceClient client) {
            this.client = requireNonNull(client, "client is required");
        }

        @Override
        public ServerStatusResponse call() throws Exception {
            return client.serverStatus(new ServerStatusRequest());
        }
    }
}
