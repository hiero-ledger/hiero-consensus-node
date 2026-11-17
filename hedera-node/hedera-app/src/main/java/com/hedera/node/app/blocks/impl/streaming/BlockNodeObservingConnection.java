// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.AcknowledgeOnlyBytes;
import com.hedera.hapi.block.internal.PublishStreamRequestBytes;
import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.BlockAcknowledgement;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.PublishStreamResponse.EndOfStream.Code;
import org.hiero.block.api.PublishStreamResponse.ResponseOneOfType;

/**
 * A connection to a block node whose purpose is to "observe" block acknowledgements. This connection will periodically
 * send a block acknowledgement request, and then pass along any received block acknowledgements to the buffer. Aside
 * from block acknowledgements and end of streams, no other response from the block node will be handled.
 */
public class BlockNodeObservingConnection extends BlockNodePublisherConnection {

    private static final Logger logger = LogManager.getLogger(BlockNodeObservingConnection.class);

    private final BlockBufferService bufferService;
    /**
     * Reference to the worker thread, once it is initialized.
     */
    private final AtomicReference<Thread> workerThreadRef = new AtomicReference<>();
    /**
     * The most recent timestamp (as millis) when this connection received a block acknowledgement.
     */
    private final AtomicLong latestAckUpdateMillis = new AtomicLong(-1);
    /**
     * Local copy of the highest acknowledged block over this connection.
     */
    private final AtomicLong localHighestAckedBlock = new AtomicLong(-1);

    public BlockNodeObservingConnection(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockNode blockNode,
            @NonNull final BlockNodeConnectionManager connectionManager,
            @NonNull final BlockBufferService bufferService,
            @NonNull final BlockStreamMetrics metrics,
            @NonNull final ExecutorService blockingIoExecutor,
            @NonNull final BlockNodeClientFactory clientFactory,
            final long nodeId) {
        super(
                configProvider,
                blockNode,
                ConnectionType.ACK_OBSERVING,
                clientFactory,
                nodeId,
                metrics,
                blockingIoExecutor,
                bufferService,
                connectionManager);

        this.bufferService = requireNonNull(bufferService);
    }

    @Override
    public void onNext(final PublishStreamResponse response) throws RuntimeException {
        requireNonNull(response);

        if (currentState() == ConnectionState.CLOSED) {
            logger.debug(
                    "{} Received response from block node, but this connection is closed (response: {})",
                    this,
                    response);
            return;
        }

        if (response.hasAcknowledgement()) {
            handleAcknowledgement(response.acknowledgement());
        } else if (response.hasEndStream()) {
            handleEndStream(response.endStream());
        } else {
            metrics().recordIgnoredResponseWhileObserving();
            logger.trace(
                    "{} Unaccepted response received: {}",
                    this,
                    response.response().kind());
        }
    }

    /**
     * Handle a block acknowledgement received from the block node.
     *
     * @param ack the acknowledgement received
     */
    private void handleAcknowledgement(@NonNull final BlockAcknowledgement ack) {
        requireNonNull(ack);

        final long acknowledgedBlockNumber = ack.blockNumber();

        metrics().recordResponseReceived(connectionType(), ResponseOneOfType.ACKNOWLEDGEMENT);
        logger.debug("{} Acknowledgement received for block {}", this, acknowledgedBlockNumber);

        bufferService.acknowledgeBlock(
                acknowledgedBlockNumber, blockNode().configuration().streamingEndpoint());
        final long prevAckedBlock = localHighestAckedBlock.getAndUpdate(old -> Math.max(old, acknowledgedBlockNumber));
        if (prevAckedBlock < acknowledgedBlockNumber) {
            latestAckUpdateMillis.updateAndGet(old -> Math.max(old, System.currentTimeMillis()));
        }

        connectionStatistics().recordAcknowledgement(acknowledgedBlockNumber);
    }

    /**
     * Handle a block node end of stream response.
     *
     * @param eos the end stream response received
     */
    private void handleEndStream(@NonNull final EndOfStream eos) {
        requireNonNull(eos);

        final long lastVerifiedBlock = eos.blockNumber();
        final long approxProblematicBlock = eos.proximateBlockNumber();
        final EndOfStream.Code responseCode = eos.status();

        metrics().recordResponseEndOfStreamReceived(connectionType(), responseCode);
        metrics().recordLatestBlockEndOfStream(lastVerifiedBlock);
        logger.info(
                "{} Received EndOfStream response (lastVerifiedBlock: {}, approxProblematicBlock: {}, responseCode: {})",
                this,
                lastVerifiedBlock,
                approxProblematicBlock,
                responseCode);

        logger.debug(
                "{} Acknowledging the last verified block ({}) from the EndOfStream response", this, lastVerifiedBlock);
        bufferService.acknowledgeBlock(
                lastVerifiedBlock, blockNode().configuration().streamingEndpoint());

        switch (responseCode) {
            case Code.TIMEOUT, Code.DUPLICATE_BLOCK, Code.BAD_BLOCK_PROOF, Code.INVALID_REQUEST -> {
                // A transient issue happened on the BN, so still close the connection but don't force the block node
                // to enter a cool-down period.
                close(CloseReason.TRANSIENT_END_STREAM_RECEIVED, true, null);
            }
            default -> {
                // A non-transient close reason was received, so close the connection and let the block node go into
                // a cool-down period
                close(CloseReason.END_STREAM_RECEIVED, true, null);
            }
        }
    }

    @Override
    void startWorkerThread() {
        final Thread workerThread = new Thread(new ConnectionWorkerLoopTask(), "bn-conn-worker-" + connectionId());
        if (workerThreadRef.compareAndSet(null, workerThread)) {
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    /**
     * Worker that handles sending AcknowledgeOnly requests to the block node periodically.
     */
    private class ConnectionWorkerLoopTask implements Runnable {

        private long firstRequestMillis = -1;
        /**
         * The most recent time an AcknowledgeOnly request was successfully sent to the block node.
         */
        private long latestRequestMillis = -1;

        @Override
        public void run() {
            logger.info("{} Worker thread started (observer)", BlockNodeObservingConnection.this);

            while (true) {
                try {
                    if (currentState().isTerminal()) {
                        break;
                    }

                    doWork();

                    if (currentState().isTerminal()) {
                        break;
                    }

                    final long sleepMillis =
                            bncConfig().connectionWorkerSleepDuration().toMillis();
                    Thread.sleep(sleepMillis);
                } catch (final InterruptedException _) {
                    Thread.currentThread().interrupt();
                    logger.warn("{} Worker loop was interrupted", BlockNodeObservingConnection.this);
                } catch (final Exception e) {
                    logger.warn("{} Error caught in connection worker loop", BlockNodeObservingConnection.this, e);
                }
            }

            // if we exit the worker loop, then this thread is over... remove it from the worker thread reference
            logger.info("{} Worker thread exiting", BlockNodeObservingConnection.this);
            workerThreadRef.compareAndSet(Thread.currentThread(), null);
        }

        private void doWork() {
            connectionStatistics().recordHeartbeat(System.currentTimeMillis());

            if (isIdle()) {
                return;
            }

            sendAckRequest();
        }

        /**
         * Checks if this connection is idle. In this case, idle means not receiving block acknowledgements from the
         * block node, and specifically acknowledgements that increase the high watermark.
         *
         * @return true if this connection is considered idle and closed, else false
         */
        private boolean isIdle() {
            final long lastBlockAckedLocally = localHighestAckedBlock.get();
            final long lastBlockClosed = bufferService.getLastBlockClosed();
            final long lastBlockAckedGlobally = bufferService.getHighestAckedBlockNumber();

            if (lastBlockAckedGlobally < lastBlockClosed && lastBlockAckedLocally < lastBlockClosed) {
                // the latest block closed in the buffer doesn't match the latest block acknowledged on this connection
                // check to see if too much time has elapsed since the last acknowledgement
                final long nowMillis = System.currentTimeMillis();
                final long millisSinceFirstReq = nowMillis - firstRequestMillis;
                final long maxIdleSeconds = bncConfig().maxObserverIdleSeconds();
                final long maxIdleMillis = maxIdleSeconds * 1_000L;

                if (firstRequestMillis == -1 || millisSinceFirstReq < maxIdleMillis) {
                    /*
                    Allow for a grace period at the beginning of this connection. This grace period is set as the period
                    between when the very first AcknowledgeOnly request was sent on this connection and the max idle
                    seconds after that. During this period, we do not check for acknowledgement advancement since the
                    block node may not yet have responded to our request. After this grace period, a lack of advancement
                    will be considered a deviation.
                     */
                    logger.trace("{} Connection is still in initial grace period", BlockNodeObservingConnection.this);
                    return false;
                }

                final long millisSinceLastAck = nowMillis - latestAckUpdateMillis.get();

                if (millisSinceLastAck > maxIdleMillis) {
                    /*
                    This connection has sent at least one AcknowledgeOnly request thus far. However, it has not received
                    any block acknowledgements that advanced the high watermark for this connection is sufficient time.
                    Close this connection so hopefully a new one can be established to a more lively block node.
                     */
                    logger.warn(
                            "{} Observer hasn't advanced highest block acknowledged in over {} seconds; closing connection",
                            BlockNodeObservingConnection.this,
                            maxIdleSeconds);
                    close(CloseReason.IDLE_RECEIVE, true, EndStream.Code.TIMEOUT);
                    return true;
                } else {
                    logger.trace(
                            "{} Connection is behind but not idle (millisIdle: {})",
                            BlockNodeObservingConnection.this,
                            millisSinceLastAck);
                }
            }

            return false;
        }

        /**
         * Prepare and send the AcknowledgeOnly request to the block node.
         */
        private void sendAckRequest() {
            final long blockPeriodMillis = bsConfig().blockPeriod().toMillis();
            final long millisSinceLastRequest = System.currentTimeMillis() - latestRequestMillis;

            if (millisSinceLastRequest < blockPeriodMillis) {
                // not enough time has elapsed since the last request was sent
                return;
            }

            // it's been long enough since the last request so we can send another one
            final long latestBlockNumber = bufferService.getLastBlockNumberProduced();
            final long latestBlockAcknowledged = bufferService.getHighestAckedBlockNumber();

            if (latestBlockNumber == -1 || latestBlockNumber == latestBlockAcknowledged) {
                // no new, unacked blocks have been produced yet
                latestAckUpdateMillis.set(System.currentTimeMillis());
                logger.trace(
                        "{} No new blocks produced to request acknowledgement for (latestProduced: {}, latestAcked: {}); skipping",
                        BlockNodeObservingConnection.this,
                        latestBlockNumber,
                        latestBlockAcknowledged);
                return;
            }

            // use the latest block produced, even though it may not be fully complete yet
            // it's possible that this consensus node is behind others and we want to try to get the tip of the block
            // stream acknowledged as fast as possible
            final PublishStreamRequestBytes request = PublishStreamRequestBytes.newBuilder()
                    .acknowledgeOnly(AcknowledgeOnlyBytes.newBuilder()
                            .blockNumber(latestBlockNumber)
                            .build())
                    .build();
            final long timeoutMillis = calculateRequestTimeout(request.protobufSize());
            final PublisherAcknowledgeOnlyRequest req = new PublisherAcknowledgeOnlyRequest(request, timeoutMillis);

            final SendRequestResult result;

            try {
                logger.debug(
                        "{} Sending AcknowledgeOnly request with block {}",
                        BlockNodeObservingConnection.this,
                        latestBlockNumber);
                result = sendRequest(req);
            } catch (final RuntimeException e) {
                logger.warn("{} Error sending AcknowledgeOnly request", BlockNodeObservingConnection.this, e);
                close(CloseReason.CONNECTION_ERROR, false, EndStream.Code.ERROR);
                return;
            }

            if (SendRequestStatus.SUCCESS == result.status()) {
                latestRequestMillis = System.currentTimeMillis();
                if (firstRequestMillis == -1) {
                    firstRequestMillis = latestRequestMillis;
                }
            } else {
                logger.warn(
                        "{} Sending AcknowledgeOnly request failed for non-exceptional reason (crn: {}, reason: {})",
                        BlockNodeObservingConnection.this,
                        result.context().crn(),
                        result.status());
            }
        }
    }
}
