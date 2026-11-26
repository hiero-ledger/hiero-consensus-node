// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
     * Metrics API for block stream-specific metrics.
     */
    private final BlockStreamMetrics metrics;
    /**
     * Counter used to generate unique request attempt IDs for the lifetime of this connection.
     */
    private final AtomicLong connectionRequestNumberGenerator = new AtomicLong(0);

    /**
     * Create a new instance.
     *
     * @param configProvider the configuration provider to use
     * @param nodeConfig the block node configuration to use for this connection
     * @param blockingIoExecutor the executor service to use for executing blocking I/O tasks
     * @param clientFactory the factory to use for creating clients to the block node
     * @param nodeId the id of the node owning this connection
     */
    public BlockNodeServiceConnection(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockNodeConfiguration nodeConfig,
            @NonNull final ExecutorService blockingIoExecutor,
            @NonNull final BlockNodeClientFactory clientFactory,
            final long nodeId,
            @NonNull final BlockStreamMetrics metrics) {
        super(ConnectionType.SERVER_STATUS, nodeConfig, configProvider, nodeId);
        this.blockingIoExecutor = requireNonNull(blockingIoExecutor, "Blocking I/O executor is required");
        this.clientFactory = requireNonNull(clientFactory, "client factory is required");
        this.metrics = requireNonNull(metrics, "metrics is required");
    }

    @Override
    void initialize() {
        if (currentState() != ConnectionState.UNINITIALIZED) {
            logger.debug("{} Connection is already in a non-uninitialized state", this);
            return;
        }

        final long timeoutMillis = connectionManagementTimeoutMillis();
        Future<?> future = null;

        try {
            future = blockingIoExecutor.submit(new CreateClientTask());
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            future.cancel(true);
            logger.warn("{} Pipeline creation timed out after {}ms", this, timeoutMillis);
            metrics.recordOperationTimeout(connectionType());
            metrics.recordConnectionCreateFailure(connectionType());
            throw new RuntimeException("Pipeline creation timed out", e);
        } catch (final InterruptedException e) {
            metrics.recordConnectionCreateFailure(connectionType());
            future.cancel(true);
            Thread.currentThread().interrupt();
            logger.warn("{} Interrupted while creating pipeline", this, e);
            throw new RuntimeException("Interrupted while creating pipeline", e);
        } catch (final Exception e) {
            metrics.recordConnectionCreateFailure(connectionType());

            final Throwable error = e instanceof final ExecutionException ee ? ee.getCause() : e;

            logger.warn("{} Error creating pipeline", this, error);
            throw new RuntimeException("Error creating pipeline", error);
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
            final BlockNodeServiceClient client = clientFactory.createServiceClient(
                    configuration(),
                    timeout,
                    connectionId().toString(),
                    bncConfig().grpcCompressionType());
            final ServiceClientHolder holder = new ServiceClientHolder(clientId, client);
            if (clientRef.compareAndSet(null, holder)) {
                // unlike the streaming connection, these connections don't really have an intermediate state between
                // UNINITIALIZED and ACTIVE, so just set the state to ACTIVE
                boolean activated;

                try {
                    activated = updateConnectionState(ConnectionState.UNINITIALIZED, ConnectionState.ACTIVE);
                } catch (final IllegalConnectionStateTransition _) {
                    activated = false;
                }

                if (!activated) {
                    if (clientRef.compareAndSet(holder, null)) {
                        closeSilently(holder);
                    }
                    return;
                }

                metrics.recordConnectionOpened(connectionType());
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
                // this task already runs on the executor, which may be shutting down, so close directly
                holder.client.close();
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
        /**
         * Claimed by the first thread to run this task, so the client is closed at most once.
         */
        private final AtomicBoolean started = new AtomicBoolean(false);

        CloseClientTask(@NonNull final ServiceClientHolder clientHolder) {
            this.clientHolder = requireNonNull(clientHolder, "client is required");
        }

        @Override
        public void run() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            logger.debug("{} Closing client (clientId: {})", BlockNodeServiceConnection.this, clientHolder.clientId);
            clientHolder.client.close();
        }
    }

    @Override
    public void close() {
        boolean wasActive = false;
        ConnectionState state;

        try {
            do {
                state = currentState();
                wasActive |= state == ConnectionState.ACTIVE;
                if (state.isTerminal()) {
                    // either close has already been called or close was called while the connection wasn't initialized
                    return;
                }
            } while (!updateConnectionState(state, ConnectionState.CLOSING));
        } catch (final IllegalConnectionStateTransition _) {
            // another thread has transitioned this connection to a later stage
            return;
        }

        final ServiceClientHolder clientHolder = clientRef.getAndSet(null);

        logger.info("{} Closing connection", this);

        boolean wasInterrupted = Thread.interrupted(); // drain pre-existing interrupt flag

        try {
            if (clientHolder != null) {
                final CloseClientTask closeTask = new CloseClientTask(clientHolder);
                try {
                    blockingIoExecutor
                            .submit(closeTask)
                            .get(connectionManagementTimeoutMillis(), TimeUnit.MILLISECONDS);
                } catch (final Exception e) {
                    // the connection is being closed... don't propagate the exception
                    logger.warn("{} Error occurred while closing connection; it will be suppressed", this, e);
                    wasInterrupted |= e instanceof InterruptedException;
                    // rejected or not started yet: close here; a no-op if the executor already started it, which is
                    // left to finish
                    try {
                        closeTask.run();
                    } catch (final RuntimeException ce) {
                        logger.warn("{} Error occurred while closing client; it will be suppressed", this, ce);
                    }
                }
            }
        } finally {
            // regardless of outcome, mark this connection as closed
            if (wasActive) {
                // only record the connection closed for metrics _if_ we actually opened the connection
                metrics.recordConnectionClosed(connectionType(), CloseReason.OBSOLETE);
            }
            updateConnectionState(ConnectionState.CLOSED);
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
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

        final long crn = connectionRequestNumberGenerator.incrementAndGet();
        final String correlationId = buildRequestCorrelationId(crn);
        final long timeoutMillis = statusTimeoutMillis();
        Future<ServerStatusResponse> future = null;
        final ServerStatusResponse response;
        final long startNanos = System.nanoTime();
        long endNanos;

        try {
            future = blockingIoExecutor.submit(new GetBlockNodeStatusTask(clientHolder.client));
            response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            endNanos = System.nanoTime();
        } catch (final TimeoutException _) {
            endNanos = System.nanoTime();
            future.cancel(true);
            return handleSendTimeout(correlationId, startNanos, endNanos, timeoutMillis);
        } catch (final InterruptedException _) {
            future.cancel(true);
            endNanos = System.nanoTime();
            return handleSendInterrupt(correlationId, startNanos, endNanos);
        } catch (final Exception e) {
            endNanos = System.nanoTime();
            return handleSendError(correlationId, startNanos, endNanos, e);
        }

        final long durationNanos = endNanos - startNanos;
        final long durationMicros = TimeUnit.NANOSECONDS.toMicros(durationNanos);
        metrics.recordRequestLatency(connectionType(), durationMicros);

        if (response == null) {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "{} Successfully sent a server status request to block node, but empty response received - marking node as unreachable",
                        connectionContext(correlationId));
            }
            return BlockNodeStatus.notReachable();
        }

        metrics.recordServiceResponseReceived();

        logger.debug(
                "{} Received the following block node server status => nextExpectedBlock: {}, latency: {}μs",
                this,
                response.nextExpectedBlock(),
                durationMicros);

        return BlockNodeStatus.reachable(durationMicros, response.nextExpectedBlock());
    }

    /**
     * Handle an error encountered when sending a request.
     *
     * @param correlationId the correlation ID of the request
     * @param startNanos the nanosecond tick when the request send started
     * @param endNanos the nanosecond tick when the request send completed
     * @param e the error encountered
     * @return the block node status
     */
    private BlockNodeStatus handleSendError(
            final String correlationId, final long startNanos, final long endNanos, final Exception e) {
        metrics.recordRequestSendFailure(connectionType());
        final long durationMicros = calculateDurationMicros(startNanos, endNanos);
        final Throwable error = e instanceof ExecutionException ? e.getCause() : e;
        final FailureType failureType = FailureType.findFailureType(error);

        if (logger.isWarnEnabled()) {
            if (failureType.isCommonFailure()) {
                logger.warn(
                        "{} Error encountered while sending request to block node (duration: {}μs, error: {})",
                        connectionContext(correlationId),
                        durationMicros,
                        failureType);
            } else {
                logger.warn(
                        "{} Error encountered while sending request to block node (duration: {}μs)",
                        connectionContext(correlationId),
                        durationMicros,
                        error);
            }
        }

        return BlockNodeStatus.notReachable();
    }

    /**
     * Handle an interrupt encountered when sending a request.
     *
     * @param correlationId the correlation ID of the request
     * @param startNanos the nanosecond tick when the request send started
     * @param endNanos the nanosecond tick when the request send completed
     * @return the block node status
     */
    private BlockNodeStatus handleSendInterrupt(
            final String correlationId, final long startNanos, final long endNanos) {
        metrics.recordRequestSendFailure(connectionType());
        final long durationMicros = calculateDurationMicros(startNanos, endNanos);

        Thread.currentThread().interrupt();

        if (logger.isWarnEnabled()) {
            logger.warn(
                    "{} Interrupted while sending request to block node (duration: {}μs)",
                    connectionContext(correlationId),
                    durationMicros);
        }

        return BlockNodeStatus.notReachable();
    }

    /**
     * Handle a timeout encountered when sending a request.
     *
     * @param correlationId the correlation ID of the request
     * @param startNanos the nanosecond tick when the request send started
     * @param endNanos the nanosecond tick when the request send completed
     * @param timeoutMillis the timeout (in milliseconds) associated with the request
     * @return the block node status
     */
    private BlockNodeStatus handleSendTimeout(
            final String correlationId, final long startNanos, final long endNanos, final long timeoutMillis) {
        metrics.recordRequestSendFailure(connectionType());
        metrics.recordOperationTimeout(connectionType());
        final long durationMicros = calculateDurationMicros(startNanos, endNanos);

        if (logger.isWarnEnabled()) {
            logger.warn(
                    "{} Timed out sending request to block node (timeout: {}ms, duration: {}μs)",
                    connectionContext(correlationId),
                    timeoutMillis,
                    durationMicros);
        }

        return BlockNodeStatus.notReachable();
    }

    /**
     * Task to get the server status.
     */
    class GetBlockNodeStatusTask implements Callable<ServerStatusResponse> {

        /**
         * The client to use
         */
        private final BlockNodeServiceClient client;

        private final String correlationId;

        GetBlockNodeStatusTask(@NonNull final BlockNodeServiceClient client) {
            this.client = requireNonNull(client, "client is required");
            this.correlationId = connectionId().toString();
        }

        @Override
        public ServerStatusResponse call() throws Exception {
            metrics.recordServiceRequestSent();
            final ServerStatusRequest req = new ServerStatusRequest();
            metrics.recordRequestBytes(connectionType(), req.protobufSize());

            return client.serverStatus(req, clientFactory.requestOptionsForCorrelationId(correlationId));
        }
    }
}
