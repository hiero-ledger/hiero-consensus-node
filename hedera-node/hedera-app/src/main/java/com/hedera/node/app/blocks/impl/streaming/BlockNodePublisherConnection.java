// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.EndStreamBytes;
import com.hedera.hapi.block.internal.PublishStreamRequestBytes;
import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.grpc.GrpcCall;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.Pipeline;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamResponse;

/**
 * A connection to a block node that utilizing the publisher API.
 */
public abstract class BlockNodePublisherConnection extends AbstractBlockNodeConnection
        implements Pipeline<PublishStreamResponse> {

    private static final Logger logger = LogManager.getLogger(BlockNodePublisherConnection.class);
    /**
     * Default window (in milliseconds) for timeouts.
     */
    private static final long DEFAULT_TIMEOUT_WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(60);
    /**
     * Metrics API for block stream-specific metrics.
     */
    private final BlockStreamMetrics metrics;
    /**
     * The bidirectional gRPC call interface used to send messages to the block node.
     */
    private final AtomicReference<GrpcCall<PublishStreamRequestBytes, PublishStreamResponse>> requestCallRef =
            new AtomicReference<>();
    /**
     * Executor service used to perform asynchronous, blocking I/O operations.
     */
    private final ExecutorService blockingIoExecutor;
    /**
     * Factory used to create the block node clients.
     */
    private final BlockNodeClientFactory clientFactory;
    /**
     * The block node associated with this connection.
     */
    private final BlockNode blockNode;
    /**
     * Publish gRPC client used to send messages to the block node.
     */
    private BlockStreamPublishBytesClient client;
    /**
     * Counter used to generate unique request attempt IDs for the lifetime of this connection.
     */
    private final AtomicLong connectionRequestNumberGenerator = new AtomicLong(0);
    /**
     * Set of the most recent timestamps (as milliseconds) when an operations timed out.
     */
    private final SortedSet<Long> mostRecentOpTimeoutTimestamps = Collections.synchronizedSortedSet(new TreeSet<>());
    /**
     * Statistics related to this streaming connection.
     */
    private final StreamingConnectionStatistics connStats;
    /**
     * Flag that indicates if this stream is currently shutting down, as initiated by this consensus node.
     */
    private final AtomicBoolean streamShutdownInProgress = new AtomicBoolean(false);
    /**
     * Manager that maintains the system-wide state as it pertains to block streaming. Access here is used to retrieve
     * blocks for streaming and indicating which blocks have been acknowledged by the block node.
     */
    private final BlockBufferService bufferService;
    /**
     * Flag indicating whether an end stream has been sent for this connection.
     */
    private final AtomicBoolean isEndStreamSent = new AtomicBoolean(false);
    /**
     * The "parent" connection manager that manages the lifecycle of this connection.
     */
    private final BlockNodeConnectionManager connectionManager;

    public BlockNodePublisherConnection(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockNode blockNode,
            @NonNull final ConnectionType connectionType,
            @NonNull final BlockNodeClientFactory clientFactory,
            final long nodeId,
            @NonNull final BlockStreamMetrics metrics,
            @NonNull final ExecutorService blockingIoExecutor,
            @NonNull final BlockBufferService bufferService,
            @NonNull final BlockNodeConnectionManager connectionManager) {
        super(connectionType, blockNode.configuration(), configProvider, nodeId);
        this.blockNode = blockNode;
        this.clientFactory = requireNonNull(clientFactory, "Client factory must not be null");
        this.metrics = requireNonNull(metrics, "Blocking streaming metrics must not be null");
        this.blockingIoExecutor = requireNonNull(blockingIoExecutor, "Blocking I/O executor must not be null");
        this.bufferService = requireNonNull(bufferService, "Buffer service must not be null");
        this.connectionManager = requireNonNull(connectionManager, "Connection manager must not be null");

        connStats = new StreamingConnectionStatistics();
    }

    /**
     * @return the connection statistics related to this connection
     */
    @NonNull
    final StreamingConnectionStatistics connectionStatistics() {
        return connStats;
    }

    /**
     * @return the connection manager
     */
    @NonNull
    final BlockNodeConnectionManager connectionManager() {
        return connectionManager;
    }

    /**
     * @return the metrics instance
     */
    @NonNull
    final BlockStreamMetrics metrics() {
        return metrics;
    }

    /**
     * @return the block node associated with this connection
     */
    @NonNull
    final BlockNode blockNode() {
        return blockNode;
    }

    /**
     * @return the block buffer instance
     */
    @NonNull
    final BlockBufferService bufferService() {
        return bufferService;
    }

    /**
     * @return true if the request client is initialized, else false
     */
    final boolean isRequestClientActive() {
        return requestCallRef.get() != null;
    }

    @Override
    public final synchronized void initialize() {
        if (isRequestClientActive()) {
            logger.debug("{} Connection already initialized", this);
            return;
        }

        final Duration timeoutDuration = bncConfig().grpcOverallTimeout();

        // Execute entire pipeline creation (including gRPC client creation) with timeout
        // to prevent blocking on network operations
        final Future<?> future = blockingIoExecutor.submit(() -> {
            client = clientFactory.createStreamingClient(
                    configuration(),
                    timeoutDuration,
                    connectionId().toString(),
                    bncConfig().grpcCompressionType());
            final GrpcCall<PublishStreamRequestBytes, PublishStreamResponse> call = client.publishBlockStream(this);
            requestCallRef.set(call);
        });

        final long timeoutMillis = connectionManagementTimeoutMillis();

        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (logger.isDebugEnabled()) {
                logger.debug("{} Request pipeline initialized (success: {})", this, isRequestClientActive());
            }
            updateConnectionState(ConnectionState.READY);
            metrics.recordConnectionOpened();
        } catch (final TimeoutException e) {
            future.cancel(true);
            logger.warn("{} Pipeline creation timed out after {}ms", this, timeoutMillis);
            metrics.recordPipelineOperationTimeout();
            throw new RuntimeException("Pipeline creation timed out", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("{} Interrupted while creating pipeline", this, e);
            throw new RuntimeException("Interrupted while creating pipeline", e);
        } catch (final ExecutionException e) {
            logger.warn("{} Error creating pipeline", this, e.getCause());
            throw new RuntimeException("Error creating pipeline", e.getCause());
        }
    }

    /**
     * Calculate the timeout to use for sending a request, based on the size of the request.
     *
     * @param payloadSizeInBytes the size (in bytes) of the request intended to send
     * @return the timeout in milliseconds
     */
    final long calculateRequestTimeout(final long payloadSizeInBytes) {
        final long baseMillis = bncConfig().opTimeoutBaseMillis();
        final long microsPerKb = bncConfig().opTimeoutMicrosPerKilobyte();
        final long maxMillis = bncConfig().opTimeoutMaxMillis();

        final long kilobytes = (payloadSizeInBytes / BlockStreamingUtils.KB_TO_BYTES) + 1;
        final long microsToAdd = kilobytes * microsPerKb;
        final long millisToAdd = TimeUnit.MICROSECONDS.toMillis(microsToAdd);
        final long timeout = baseMillis + millisToAdd;

        return Math.min(timeout, maxMillis);
    }

    /**
     * Calculates the duration between the specified start and end nanoseconds and converts it to microseconds.
     *
     * @param startNanos the starting nanosecond timestamp
     * @param endNanos the ending nanosecond timestamp
     * @return the duration between the start and end in microseconds
     */
    private static long calculateDurationMicros(final long startNanos, final long endNanos) {
        final long totalNanos;

        if (startNanos == -1 && endNanos == -1) {
            // if both start and end are -1 it probably means the request never actually was attempted
            return -1;
        } else if (endNanos == -1) {
            // if the endNanos is -1 it probably means the request failed so default to the current time as the end
            totalNanos = System.nanoTime() - startNanos;
        } else {
            totalNanos = endNanos - startNanos;
        }

        return TimeUnit.NANOSECONDS.toMicros(totalNanos);
    }

    @Override
    public final void close() {
        close(CloseReason.UNKNOWN, true, null);
    }

    @Override
    public void onSubscribe(final Flow.Subscription subscription) {
        logger.debug("{} OnSubscribe invoked", this);
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void clientEndStreamReceived() {
        logger.debug("{} Client End Stream received", this);
        Pipeline.super.clientEndStreamReceived();
    }

    @Override
    public void onError(final Throwable throwable) {
        if (currentState().isTerminal()) {
            return; // ignore errors when this connection is in a terminal state
        }

        metrics.recordConnectionOnError();

        if (throwable instanceof final GrpcException grpcException) {
            logger.warn("{} Error received (grpcStatus: {})", this, grpcException.status(), grpcException);
        } else {
            final FailureType failureType = FailureType.findFailureType(throwable);
            if (failureType.isCommonFailure()) {
                logger.warn("{} Error received (error: {})", this, failureType);
            } else {
                logger.warn("{} Error received", this, throwable);
            }
        }

        close(CloseReason.CONNECTION_ERROR, true, null);
    }

    @Override
    public void onComplete() {
        metrics.recordConnectionOnComplete();

        if (currentState() == ConnectionState.CLOSED) {
            logger.debug("{} onComplete invoked but connection is already closed", this);
            return;
        }

        if (streamShutdownInProgress.getAndSet(false)) {
            logger.debug("{} Stream completed (stream close was already in progress)", this);
        } else {
            logger.warn("{} Stream completed unexpectedly", this);
            close(CloseReason.CONNECTION_ERROR, true, null);
        }
    }

    /**
     * Closes this connection. If an end stream code is specified, then before the connection is closed an attempt will
     * be made to send the aforementioned end stream message to the block node.
     *
     * @param closeReason the reason for closing this connection
     * @param callOnComplete true if a signal should be sent to the request client that no more requests are expected
     * @param endStreamCode (optional) the code to send as part of an EndStream message before the connection is closed, else false
     */
    final void close(
            @NonNull final CloseReason closeReason,
            final boolean callOnComplete,
            @Nullable final EndStream.Code endStreamCode) {
        requireNonNull(closeReason);

        final ConnectionState connectionState = currentState();
        if (connectionState.isTerminal()) {
            logger.debug("{} Connection already in terminal state ({})", this, connectionState);
            return;
        }

        if (endStreamCode != null) {
            sendEndStream(endStreamCode);
        }

        if (!updateConnectionState(connectionState, ConnectionState.CLOSING)) {
            logger.debug("{} State changed while trying to close connection. Aborting close attempt.", this);
            return;
        }

        logger.info("{} Closing connection (reason: {})", this, closeReason);
        setCloseReason(closeReason);

        try {
            closeRequestClient(callOnComplete);
        } catch (final Exception e) {
            final FailureType failureType = FailureType.findFailureType(e);
            if (failureType.isCommonFailure()) {
                logger.warn("{} Error occurred while attempting to close connection (error: {})", this, failureType);
            } else {
                logger.warn("{} Error occurred while attempting to close connection", this, e);
            }
        }

        shutdownClient();
        shutdownExecutor();

        metrics.recordConnectionClosed(closeReason);
        updateConnectionState(ConnectionState.CLOSED);
        connectionManager.notifyConnectionClosed(this);

        try {
            postClose();
        } catch (final Exception _) {
            // ignore any failure
        }
    }

    /**
     * Closes the block node client. If there are any failures, they are logged and not propagated back to the caller.
     */
    private void shutdownClient() {
        try {
            if (client != null) {
                client.close();
            }
        } catch (final Exception e) {
            final FailureType failureType = FailureType.findFailureType(e);
            if (failureType.isCommonFailure()) {
                logger.error("{} Error occurred while closing gRPC client (error: {})", this, failureType);
            } else {
                logger.error("{} Error occurred while closing gRPC client", this, e);
            }
        }
    }

    /**
     * Shuts down the blocking I/O executor service. If there are any failures, they are logged and not propagated back
     * to the caller.
     */
    private void shutdownExecutor() {
        try {
            blockingIoExecutor.shutdown();
            if (!blockingIoExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                blockingIoExecutor.shutdownNow();
            }
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            blockingIoExecutor.shutdownNow();
            logger.error("{} Error occurred while shutting down executor service (error: INTERRUPTED)", this);
        } catch (final Exception e) {
            final FailureType failureType = FailureType.findFailureType(e);
            if (failureType.isCommonFailure()) {
                logger.error("{} Error occurred while shutting down executor service (error: {})", this, failureType);
            } else {
                logger.error("{} Error occurred while shutting down executor service", this, e);
            }
        }
    }

    /**
     * Closes the request client to the block node.
     *
     * @param callOnComplete true if the client should be notified that no more requests are expected, else false
     */
    private void closeRequestClient(final boolean callOnComplete) {
        final GrpcCall<PublishStreamRequestBytes, PublishStreamResponse> call = requestCallRef.get();

        if (call == null) {
            return; // there is nothing to close
        }

        logger.debug("{} Closing request client", this);
        streamShutdownInProgress.set(true);

        final long timeoutMillis = connectionManagementTimeoutMillis();

        try {
            if (currentState() == ConnectionState.CLOSING && callOnComplete) {
                final Future<?> future = blockingIoExecutor.submit(call::completeRequests);
                try {
                    future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                    logger.debug("{} Request client successfully closed", this);
                } catch (final TimeoutException _) {
                    future.cancel(true); // Cancel the task if it times out
                    logger.warn(
                            "{} Timed out while attempting to shutdown request client (timeout: {}ms) - ignoring",
                            this,
                            timeoutMillis);
                    metrics.recordPipelineOperationTimeout();
                    // Connection is already closing, just log the timeout
                } catch (final InterruptedException _) {
                    Thread.currentThread().interrupt(); // Restore interrupt status
                    logger.debug("{} Interrupted while waiting for request client to close", this);
                } catch (final ExecutionException e) {
                    final FailureType failureType = FailureType.findFailureType(e);
                    if (failureType.isCommonFailure()) {
                        logger.debug("{} Error executing request client close (error: {})", this, failureType);
                    } else {
                        logger.debug("{} Error executing request client close", this, e.getCause());
                    }
                }
            }
        } catch (final Exception e) {
            final FailureType failureType = FailureType.findFailureType(e);
            if (failureType.isCommonFailure()) {
                logger.warn("{} Error while completing request client (error: {})", this, failureType);
            } else {
                logger.warn("{} Error while completing request client", this, e);
            }
        }
        // Clear the call reference to prevent further use
        logger.debug("{} Request client closed and cleared", this);
        requestCallRef.compareAndSet(call, null);
    }

    /**
     * Invoked after the connection has been closed. Implementations of this class may override this method if they have
     * special operations they want to do after the connection has been closed.
     */
    void postClose() {
        // no-op
    }

    private void sendEndStream(@NonNull final PublishStreamRequest.EndStream.Code endStreamCode) {
        requireNonNull(endStreamCode);

        if (isEndStreamSent.get()) {
            return; // we've already sent an EndStream request OR we are avoiding sending one
        }

        final long earliestBlockNumber = bufferService.getEarliestAvailableBlockNumber();
        final long highestAckedBlockNumber = bufferService.getHighestAckedBlockNumber();

        final PublishStreamRequestBytes endStream = PublishStreamRequestBytes.newBuilder()
                .endStream(EndStreamBytes.newBuilder()
                        .endCode(endStreamCode)
                        .earliestBlockNumber(earliestBlockNumber)
                        .latestBlockNumber(highestAckedBlockNumber))
                .build();

        logger.info(
                "{} Attempting to send EndStream (code: {}, earliestBlock: {}, latestAcked: {})",
                this,
                endStreamCode,
                earliestBlockNumber,
                highestAckedBlockNumber);

        final long timeoutMillis = calculateRequestTimeout(endStream.protobufSize());

        try {
            isEndStreamSent.set(true); // regardless of outcome, mark it as sent
            final SendRequestResult result = sendRequest(new PublisherEndStreamRequest(endStream, timeoutMillis));
            if (SendRequestStatus.SUCCESS != result.status) {
                logger.warn("{} Sending of end stream request failed (reason: {})", this, result.status);
            }
        } catch (final RuntimeException e) {
            final FailureType failureType = FailureType.findFailureType(e);
            if (failureType.isCommonFailure()) {
                logger.warn("{} Error sending EndStream request (error: {})", this, failureType);
            } else {
                logger.warn("{} Error sending EndStream request", this, e);
            }
        }
    }

    final @NonNull SendRequestResult sendRequest(@NonNull final PublisherRequest request) {
        requireNonNull(request);

        final long crn = connectionRequestNumberGenerator.incrementAndGet();
        final String correlationId;
        if (request instanceof final PublisherBlockRequest pbr) {
            correlationId =
                    buildRequestCorrelationId(crn, pbr.blockNumber(), pbr.requestNumber(), pbr.blockAttemptNumber());
        } else {
            correlationId = buildRequestCorrelationId(crn);
        }

        if (logger.isDebugEnabled()) {
            logger.debug(
                    "{} Sending request to block node (type: {})",
                    connectionContext(correlationId),
                    request.streamRequestType());
        }

        final GrpcCall<PublishStreamRequestBytes, PublishStreamResponse> call = requestCallRef.get();

        if (!isActive() || call == null) {
            if (logger.isDebugEnabled()) {
                logger.debug(
                        "{} Tried to send a request but the connection is not active or initialized; ignoring request",
                        connectionContext(correlationId));
            }
            return new SendRequestResult(
                    SendRequestStatus.CONNECTION_CLOSED, new SendRequestContext(correlationId, crn, -1L, -1L, null));
        }

        final AtomicLong startNanos = new AtomicLong(-1);
        final AtomicLong endNanos = new AtomicLong(-1);
        final AtomicReference<Instant> sentTimestamp = new AtomicReference<>();
        Future<?> future = null;

        /*
        When handling failures, we need to be mindful of whether the connection is active at the time the exception is
        received. Since this is a multithreaded system that may be influenced by external forces (e.g. network or block
        node sending an EndOfStream response) while a request is being made, we want to avoid propagating errors when
        the connection is in a terminal state. Instead, we want to propagate errors ONLY if the connection is active at
        the time the error is received, otherwise log the event and suppress it.

        Additionally, if there is any error encountered - regardless of connection state, we want to immediately exit
        this method.
         */

        final SendRequestContext ctx;

        try {
            connStats.recordRequestSendAttempt();
            future = blockingIoExecutor.submit(() -> {
                startNanos.set(System.nanoTime());
                call.sendRequest(request.streamRequest(), false);
                endNanos.set(System.nanoTime());
                sentTimestamp.set(Instant.now());
            });
            future.get(request.timeoutMillis(), TimeUnit.MILLISECONDS);
            connStats.recordRequestSendSuccess();
        } catch (final TimeoutException _) {
            endNanos.compareAndSet(-1, System.nanoTime());
            ctx = new SendRequestContext(correlationId, crn, startNanos.get(), endNanos.get(), sentTimestamp.get());
            future.cancel(true);
            return handleSendTimeout(request, System.currentTimeMillis(), ctx);
        } catch (final InterruptedException e) {
            endNanos.compareAndSet(-1, System.nanoTime());
            ctx = new SendRequestContext(correlationId, crn, startNanos.get(), endNanos.get(), sentTimestamp.get());
            Thread.currentThread().interrupt();
            return handleSendInterrupt(e, ctx);
        } catch (final Exception e) {
            endNanos.compareAndSet(-1, System.nanoTime());
            ctx = new SendRequestContext(correlationId, crn, startNanos.get(), endNanos.get(), sentTimestamp.get());
            return handleSendError(e, ctx);
        }

        ctx = new SendRequestContext(correlationId, crn, startNanos.get(), endNanos.get(), sentTimestamp.get());
        final long durationMicros = ctx.durationMicros();
        final long durationMillis = TimeUnit.MICROSECONDS.toMillis(durationMicros);
        final long slowThresholdMillis = bncConfig().slowRequestThresholdMillis();

        if (durationMillis > slowThresholdMillis) {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "{} Slow request detected (threshold: {}ms, observed: {}ms, requestSize: {}B)",
                        connectionContext(correlationId),
                        slowThresholdMillis,
                        durationMillis,
                        request.streamRequest().protobufSize());
            }
        } else if (logger.isTraceEnabled() && request instanceof PublisherBlockRequest) {
            logger.trace(
                    "{} Request successfully sent (duration: {}μs)", connectionContext(correlationId), durationMicros);
        }

        final SendRequestResult result = new SendRequestResult(SendRequestStatus.SUCCESS, ctx);

        updateSendMetrics(request, ctx);

        try {
            postSuccessfulRequestSend(request, result);
        } catch (final Exception _) {
            // ignore any failure
        }

        return result;
    }

    /**
     * Handle an unexpected error encountered when sending a request.
     *
     * @param e the error encountered
     * @param ctx context related to the failing request
     * @return the result of handling the error
     */
    private SendRequestResult handleSendError(final Exception e, final SendRequestContext ctx) {
        final long durationMicros = calculateDurationMicros(ctx.startNanos, ctx.endNanos);
        metrics.recordRequestSendFailure();
        final Throwable error = e instanceof ExecutionException ? e.getCause() : e;
        final FailureType failureType = FailureType.findFailureType(error);

        if (isActive()) {
            if (logger.isWarnEnabled()) {
                if (failureType.isCommonFailure()) {
                    logger.warn(
                            "{} Error encountered while sending request to block node (duration: {}μs, error: {})",
                            connectionContext(ctx.correlationId),
                            durationMicros,
                            failureType);
                } else {
                    logger.warn(
                            "{} Error encountered while sending request to block node (duration: {}μs)",
                            connectionContext(ctx.correlationId),
                            durationMicros,
                            error);
                }
            }

            throw new RuntimeException("Error encountered while sending request to block node", error);
        } else {
            if (logger.isInfoEnabled()) {
                if (failureType.isCommonFailure()) {
                    logger.info(
                            "{} Error encountered while sending request to block node (duration: {}μs, error: {}) - suppressing because connection is no longer active",
                            connectionContext(ctx.correlationId),
                            durationMicros,
                            failureType);
                } else {
                    logger.info(
                            "{} Error encountered while sending request to block node (duration: {}μs) - suppressing because connection is no longer active",
                            connectionContext(ctx.correlationId),
                            durationMicros,
                            error);
                }
            }

            return new SendRequestResult(SendRequestStatus.FAILURE, ctx);
        }
    }

    /**
     * Handle an interrupt encountered when sending a request.
     *
     * @param e the interrupt encountered
     * @param ctx context related to the failing request
     * @return the result of handling the interrupt
     */
    private SendRequestResult handleSendInterrupt(final InterruptedException e, final SendRequestContext ctx) {
        metrics.recordRequestSendFailure();
        final long durationMicros = calculateDurationMicros(ctx.startNanos, ctx.endNanos);

        if (isActive()) {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "{} Interrupted while sending request to block node (duration: {}μs)",
                        connectionContext(ctx.correlationId),
                        durationMicros);
            }
            throw new RuntimeException("Interrupted while sending request to block node", e);
        } else {
            if (logger.isInfoEnabled()) {
                logger.info(
                        "{} Interrupted while sending request to block node (duration: {}μs) - suppressing because connection is no longer active",
                        connectionContext(ctx.correlationId),
                        durationMicros);
            }
            return new SendRequestResult(SendRequestStatus.WAIT_INTERRUPT, ctx);
        }
    }

    /**
     * Handle a timeout encountered when sending a request.
     *
     * @param request the request that timed out
     * @param timeoutTimestamp the timestamp of when the timeout happened
     * @param ctx context related to the failing request
     * @return the result of handling the timeout
     */
    private SendRequestResult handleSendTimeout(
            final PublisherRequest request, final long timeoutTimestamp, final SendRequestContext ctx) {
        /*
        We've timed out sending a request to the block node. If we've encountered too many timeouts in a short period,
        then we need to close the connection. If we haven't reached the max timeouts, then we can retry sending the
        block. In the latter case, we must start back at the beginning of the block.
         */

        metrics.recordRequestSendFailure();
        final long durationMicros = calculateDurationMicros(ctx.startNanos, ctx.endNanos);

        if (!isActive()) {
            // the connection is no longer active... no need to process any further
            if (logger.isInfoEnabled()) {
                logger.info(
                        "{} Timed out sending request to block node (timeout: {}ms, duration: {}μs, requestSize: {}B) - suppressing because connection is no longer active",
                        connectionContext(ctx.correlationId),
                        request.timeoutMillis(),
                        durationMicros,
                        request.streamRequest().protobufSize());
            }
            return new SendRequestResult(SendRequestStatus.CONNECTION_CLOSED, ctx);
        }

        if (logger.isWarnEnabled()) {
            logger.warn(
                    "{} Timed out sending request to block node (timeout: {}ms, duration: {}μs, requestSize: {}B)",
                    connectionContext(ctx.correlationId),
                    request.timeoutMillis(),
                    durationMicros,
                    request.streamRequest().protobufSize());
        }

        metrics.recordPipelineOperationTimeout();

        mostRecentOpTimeoutTimestamps.add(timeoutTimestamp);
        // remove any timeouts outside the current window
        final Duration timeoutWindowDuration = bncConfig().opTimeoutWindowDuration();
        final int maxTimeoutsAllowed = bncConfig().opTimeoutMaxAttemptsPerWindow();
        final long timeoutWindowMillis;
        if (timeoutWindowDuration == null || timeoutWindowDuration.isNegative()) {
            timeoutWindowMillis = DEFAULT_TIMEOUT_WINDOW_MILLIS;
        } else {
            timeoutWindowMillis = timeoutWindowDuration.toMillis();
        }
        final long timeoutCutoffMillis = System.currentTimeMillis() - timeoutWindowMillis;
        mostRecentOpTimeoutTimestamps.removeIf(timeoutMillis -> timeoutMillis < timeoutCutoffMillis);

        if (mostRecentOpTimeoutTimestamps.size() >= maxTimeoutsAllowed) {
            // we've reached the max number of timeouts permitted... close the connection
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "{} Max timeouts reached for sending requests (maxAllowed: {}) - closing connection",
                        connectionContext(ctx.correlationId),
                        maxTimeoutsAllowed);
            }
            // even though we seem to be having trouble sending blocks to the block node, still try to send an EndStream
            close(CloseReason.TIMEOUT, true, EndStream.Code.ERROR);
            return new SendRequestResult(SendRequestStatus.TIMEOUT_CLOSE, ctx);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug(
                        "{} Max timeouts have not been reached; block re-send is permitted (max: {}, attempts: {})",
                        connectionContext(ctx.correlationId),
                        maxTimeoutsAllowed,
                        mostRecentOpTimeoutTimestamps.size());
            }
            return new SendRequestResult(SendRequestStatus.TIMEOUT, ctx);
        }
    }

    private void updateSendMetrics(final PublisherRequest request, final SendRequestContext ctx) {
        // spotless:off
        metrics.recordRequestLatency(ctx.durationMicros());

        switch (request) {
            case final PublisherEndStreamRequest r -> metrics.recordRequestEndStreamSent(r.code());
            case final PublisherBlockRequest br -> {
                switch (br) {
                    case final PublisherBlockEndRequest r -> metrics.recordRequestSent(r.streamRequestType());
                    case final PublisherBlockItemsRequest r -> {
                        metrics.recordRequestSent(r.streamRequestType());
                        metrics.recordBlockItemsSent(r.numItems());
                        metrics.recordRequestBlockItemCount(r.numItems());
                        metrics.recordRequestBytes(r.streamRequest().protobufSize());
                    }
                }
            }
            case final PublisherAcknowledgeOnlyRequest r -> metrics.recordRequestSent(r.streamRequestType());
        }
        // spotless:on
    }

    /**
     * Invoked after a request was successfully sent to the block node. Implementations of this class may override this
     * method if they have special operations they want to do after a request has been successfully sent.
     *
     * @param request the request that was sent
     * @param result the successful outcome of the request being sent
     */
    void postSuccessfulRequestSend(@NonNull final PublisherRequest request, @NonNull final SendRequestResult result) {
        // no-op
    }

    /**
     * Enumeration of possible statuses as a result of a request being sent.
     */
    enum SendRequestStatus {
        /**
         * Request was successful.
         */
        SUCCESS,
        /**
         * Request failed due to thread interrupt while waiting for request to complete.
         */
        WAIT_INTERRUPT,
        /**
         * Request timed out while sending.
         */
        TIMEOUT,
        /**
         * An unexpected failure happened while sending the request.
         */
        FAILURE,
        /**
         * The request wasn't sent because the connection is closed.
         */
        CONNECTION_CLOSED,
        /**
         * The request timed out and the maximum number of timeouts allowed has been reached and therefore the
         * connection is being closed.
         */
        TIMEOUT_CLOSE
    }

    /**
     * Simple data holder for context information related to a single request.
     *
     * @param correlationId the correlation ID of the request
     * @param crn the unique request number within this connection
     * @param startNanos the nanosecond tick when the request send started
     * @param endNanos the nanosecond tick when the request send completed
     * @param successfulSentTimestamp timestamp of when the request was successfully sent, null if it wasn't successful
     */
    record SendRequestContext(
            String correlationId,
            long crn,
            long startNanos,
            long endNanos,
            @Nullable Instant successfulSentTimestamp) {

        long durationMicros() {
            return calculateDurationMicros(startNanos, endNanos);
        }
    }

    /**
     * Result of an attempted sending of a request.
     *
     * @param status the outcome of the request send operation
     * @param context context information related to the request
     */
    record SendRequestResult(
            @NonNull SendRequestStatus status, @NonNull SendRequestContext context) {
        SendRequestResult {
            requireNonNull(status);
            requireNonNull(context);
        }
    }

    /**
     * Wrapper interface for a PublishStreamRequest.
     */
    sealed interface PublisherRequest
            permits PublisherEndStreamRequest, PublisherBlockRequest, PublisherAcknowledgeOnlyRequest {
        /**
         * @return the PublishStreamRequestBytes to send
         */
        @NonNull
        PublishStreamRequestBytes streamRequest();

        /**
         * @return the type of PublishStreamRequestBytes
         */
        default PublishStreamRequestBytes.RequestOneOfType streamRequestType() {
            return streamRequest().request().kind();
        }

        /**
         * @return the timeout (in milliseconds) associated with this request
         */
        long timeoutMillis();
    }

    /**
     * PublishStreamRequest that is specific to a single block.
     */
    sealed interface PublisherBlockRequest extends PublisherRequest
            permits PublisherBlockEndRequest, PublisherBlockItemsRequest {
        /**
         * @return the block number associated with this request
         */
        long blockNumber();

        /**
         * @return the request number
         */
        int requestNumber();

        /**
         * @return number of times this block ({@link #blockNumber()}) has been attempted on this connection
         */
        int blockAttemptNumber();
    }

    /**
     * A PublishStreamRequest of type EndStream.
     *
     * @param streamRequest the PublishStreamRequest to send
     */
    record PublisherEndStreamRequest(@NonNull PublishStreamRequestBytes streamRequest, long timeoutMillis)
            implements PublisherRequest {
        PublisherEndStreamRequest {
            requireNonNull(streamRequest);
        }

        /**
         * @return the EndStream.Code associated eith the request
         */
        @NonNull
        EndStream.Code code() {
            return requireNonNull(streamRequest().endStream()).endCode();
        }
    }

    /**
     * A PublishStreamRequest of type BlockEnd.
     *
     * @param streamRequest the PublishStreamRequest to send
     * @param blockNumber the block number associated with the BlockEnd request
     * @param requestNumber the request number
     * @param timeoutMillis the timeout (in milliseconds) to apply when sending this request
     * @param blockAttemptNumber number of times this block ({@link #blockNumber()}) has been attempted on this connection
     */
    record PublisherBlockEndRequest(
            @NonNull PublishStreamRequestBytes streamRequest,
            long blockNumber,
            int requestNumber,
            long timeoutMillis,
            int blockAttemptNumber)
            implements PublisherBlockRequest {
        PublisherBlockEndRequest {
            requireNonNull(streamRequest);
        }
    }

    /**
     * A PublishStreamRequest of type BlockItems.
     *
     * @param streamRequest the PublishStreamRequest to send
     * @param blockNumber the block number associated with the BlockItems request
     * @param requestNumber the request number
     * @param numItems the number of items in the request
     * @param hasBlockProof true if the request contains the block proof, else false
     * @param timeoutMillis the timeout (in milliseconds) to apply when sending this request
     * @param blockAttemptNumber number of times this block ({@link #blockNumber()}) has been attempted on this connection
     */
    record PublisherBlockItemsRequest(
            @NonNull PublishStreamRequestBytes streamRequest,
            long blockNumber,
            int requestNumber,
            int numItems,
            boolean hasBlockProof,
            boolean hasBlockHeader,
            long timeoutMillis,
            int blockAttemptNumber)
            implements PublisherBlockRequest {
        PublisherBlockItemsRequest {
            requireNonNull(streamRequest);
        }
    }

    record PublisherAcknowledgeOnlyRequest(@NonNull PublishStreamRequestBytes streamRequest, long timeoutMillis)
            implements PublisherRequest {
        PublisherAcknowledgeOnlyRequest {
            requireNonNull(streamRequest);
        }
    }
}
