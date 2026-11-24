// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.metrics;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.PublishStreamRequestBytes.RequestOneOfType;
import com.hedera.node.app.blocks.impl.streaming.CloseReason;
import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.PublishStreamResponse.ResponseOneOfType;
import org.hiero.consensus.metrics.RunningAverageMetric;

/**
 * Metrics related to the block stream service, specifically tracking responses received
 * from block nodes during publishing for the local node.
 */
@Singleton
public class BlockStreamMetrics {
    private static final String CATEGORY = "blockStream";

    private static final String GROUP_CONN = "conn";
    private static final String GROUP_CONN_SEND = "connSend";
    private static final String GROUP_CONN_RECV = "connRecv";
    private static final String GROUP_BUFFER = "buffer";
    private static final String GROUP_RECORD_HASHES = "recordHashes";

    private final Metrics metrics;

    // connection send metrics
    private final Map<ConnectionType, Counter> connSend_failureCounters = new EnumMap<>(ConnectionType.class);
    private Counter connSend_blockItemsCounter;
    private Counter connSend_multiItemRequestExceedsSoftLimitCounter;
    private Counter connSend_requestExceedsHardLimitCounter;
    private LongGauge connSend_latestBlockEndOfBlockGauge;
    private LongGauge connSend_streamingBlockNumberGauge;
    private RunningAverageMetric buffer_blockItemsPerBlock;
    private RunningAverageMetric buffer_blockItemBytes;
    private RunningAverageMetric buffer_blockBytes;
    private RunningAverageMetric connSend_requestBlockItemCount;
    private final Map<ConnectionType, Map<RequestOneOfType, Counter>> connSend_counters =
            new EnumMap<>(ConnectionType.class);
    private final Map<ConnectionType, Map<EndStream.Code, Counter>> connSend_endStreamCounters =
            new EnumMap<>(ConnectionType.class);
    private final Map<ConnectionType, RunningAverageMetric> connSend_RequestBytesMetrics =
            new EnumMap<>(ConnectionType.class);
    private final Map<ConnectionType, RunningAverageMetric> connSend_requestLatencyMetrics =
            new EnumMap<>(ConnectionType.class);

    // connection receive metrics
    private final Map<ConnectionType, Map<EndOfStream.Code, Counter>> connRecv_endOfStreamCounters =
            new EnumMap<>(ConnectionType.class);
    private final Map<ConnectionType, Map<ResponseOneOfType, Counter>> connRecv_counters =
            new EnumMap<>(ConnectionType.class);
    private final Map<ConnectionType, Counter> connRecv_unknownCounters = new EnumMap<>(ConnectionType.class);
    private LongGauge connRecv_latestBlockEndOfStreamGauge;
    private LongGauge connRecv_latestBlockSkipBlockGauge;
    private LongGauge connRecv_latestBlockResendBlockGauge;
    private LongGauge connRecv_latestBlockBehindPublisherGauge;
    private Counter connRecv_observerIgnored;

    // connectivity metrics
    private final Map<ConnectionType, Counter> conn_onCompleteCounters = new EnumMap<>(ConnectionType.class);
    private final Map<ConnectionType, Counter> conn_onErrorCounters = new EnumMap<>(ConnectionType.class);
    private final Map<ConnectionType, Counter> conn_openedCounters = new EnumMap<>(ConnectionType.class);
    private final Map<ConnectionType, Counter> conn_closedCounters = new EnumMap<>(ConnectionType.class);
    private final Map<ConnectionType, Counter> conn_createFailureCounters = new EnumMap<>(ConnectionType.class);
    private final Map<ConnectionType, Map<CloseReason, Counter>> conn_closeReasonCounters =
            new EnumMap<>(ConnectionType.class);
    private final Map<ConnectionType, Counter> conn_operationTimeoutCounters = new EnumMap<>(ConnectionType.class);
    private LongGauge conn_activeConnIpGauge;
    private Counter conn_endOfStreamLimitCounter;
    private Counter conn_highLatencyCounter;
    private RunningAverageMetric conn_headerSentToAckLatency;
    private RunningAverageMetric conn_headerProducedToAckLatency;
    private RunningAverageMetric conn_blockEndSentToAckLatency;
    private RunningAverageMetric conn_blockClosedToAckLatency;
    private RunningAverageMetric conn_headerSentToBlockEndSentLatency;
    private LongGauge conn_activeStreamingConnectionCount;
    private LongGauge conn_activeObservingConnectionCount;

    // buffer metrics
    private static final long BACK_PRESSURE_ACTIVE = 3;
    private static final long BACK_PRESSURE_RECOVERING = 2;
    private static final long BACK_PRESSURE_ACTION_STAGE = 1;
    private static final long BACK_PRESSURE_DISABLED = 0;
    private DoubleGauge buffer_saturationGauge;
    private LongGauge buffer_latestBlockOpenedGauge;
    private LongGauge buffer_latestBlockAckedGauge;
    private LongGauge buffer_backPressureStateGauge;
    private Counter buffer_numBlocksPrunedCounter;
    private Counter buffer_numBlocksOpenedCounter;
    private Counter buffer_numBlocksClosedCounter;
    private Counter buffer_numBlocksMissingCounter;
    private LongGauge buffer_oldestBlockGauge;
    private LongGauge buffer_newestBlockGauge;
    private LongGauge buffer_totBytesGauge;
    private LongGauge buffer_numBlocksGauge;
    private LongGauge buffer_numBlocksPendingAckGauge;
    private DoubleGauge buffer_unackedBlocksSaturationGauge;
    private DoubleGauge buffer_unackedBytesSaturationGauge;
    private DoubleGauge buffer_inProgressBlocksSaturationGauge;

    // wrapped record hashes (record stream) metrics
    // (FUTURE) Remove after cutover
    private LongGauge recordHashes_lowestBlockGauge;
    private LongGauge recordHashes_highestBlockGauge;
    private LongGauge recordHashes_hasGapsGauge;

    /**
     * Constructor of this class.
     *
     * @param metrics The metrics system to use
     */
    @Inject
    public BlockStreamMetrics(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);

        registerConnectionSendMetrics();
        registerConnectionRecvMetrics();
        registerConnectivityMetrics();
        registerBufferMetrics();
        registerWrappedRecordHashesMetrics();
    }

    // Buffer metrics --------------------------------------------------------------------------------------------------

    private void registerBufferMetrics() {
        final DoubleGauge.Config saturationCfg = newDoubleGauge(GROUP_BUFFER, "saturation")
                .withDescription("The overall maximum buffer saturation as a percent (0.0 to 100.0)");
        buffer_saturationGauge = metrics.getOrCreate(saturationCfg);

        final LongGauge.Config latestBlockOpenedCfg = newLongGauge(GROUP_BUFFER, "latestBlockOpened")
                .withDescription("The block number that was most recently opened");
        buffer_latestBlockOpenedGauge = metrics.getOrCreate(latestBlockOpenedCfg);

        final LongGauge.Config latestBlockAckedCfg = newLongGauge(GROUP_BUFFER, "latestBlockAcked")
                .withDescription("The block number that was most recently acknowledged");
        buffer_latestBlockAckedGauge = metrics.getOrCreate(latestBlockAckedCfg);

        final Counter.Config numBlocksPrunedCfg = newCounter(GROUP_BUFFER, "numBlocksPruned")
                .withDescription("Number of blocks pruned in the latest buffer pruning cycle");
        buffer_numBlocksPrunedCounter = metrics.getOrCreate(numBlocksPrunedCfg);

        final Counter.Config numBlocksOpenedCfg = newCounter(GROUP_BUFFER, "numBlocksOpened")
                .withDescription("Number of blocks opened/created in the block buffer");
        buffer_numBlocksOpenedCounter = metrics.getOrCreate(numBlocksOpenedCfg);

        final Counter.Config numBlocksClosedCfg = newCounter(GROUP_BUFFER, "numBlocksClosed")
                .withDescription("Number of blocks closed in the block buffer");
        buffer_numBlocksClosedCounter = metrics.getOrCreate(numBlocksClosedCfg);

        final Counter.Config numBlocksMissingCfg = newCounter(GROUP_BUFFER, "numBlocksMissing")
                .withDescription("Number of attempts to retrieve a block from the block buffer but it was missing");
        buffer_numBlocksMissingCounter = metrics.getOrCreate(numBlocksMissingCfg);

        final LongGauge.Config backPressureStateCfg = newLongGauge(GROUP_BUFFER, "backPressureState")
                .withDescription("Current state of back pressure (0=disabled, 1=action-stage, 2=recovering, 3=active)");
        buffer_backPressureStateGauge = metrics.getOrCreate(backPressureStateCfg);

        final LongGauge.Config oldestBlockCfg = newLongGauge(GROUP_BUFFER, "oldestBlock")
                .withDescription("After pruning, the oldest block in the buffer");
        buffer_oldestBlockGauge = metrics.getOrCreate(oldestBlockCfg);

        final LongGauge.Config newestBlockCfg = newLongGauge(GROUP_BUFFER, "newestBlock")
                .withDescription("After pruning, the newest block in the buffer");
        buffer_newestBlockGauge = metrics.getOrCreate(newestBlockCfg);

        final RunningAverageMetric.Config blockItemsPerBlockCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_BUFFER + "_blockItemsPerBlock")
                .withDescription("The average number of BlockItems per block in the buffer")
                .withFormat("%,.2f");
        buffer_blockItemsPerBlock = metrics.getOrCreate(blockItemsPerBlockCfg);

        final RunningAverageMetric.Config blockItemBytesCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_BUFFER + "_blockItemBytes")
                .withDescription("The average size in bytes of a BlockItem in the buffer")
                .withFormat("%,.2f");
        buffer_blockItemBytes = metrics.getOrCreate(blockItemBytesCfg);

        final RunningAverageMetric.Config blockBytesCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_BUFFER + "_blockBytes")
                .withDescription("The average size in bytes of a Block in the buffer")
                .withFormat("%,.2f");
        buffer_blockBytes = metrics.getOrCreate(blockBytesCfg);

        final LongGauge.Config bufferTotBytes = newLongGauge(GROUP_BUFFER, "totalBytes")
                .withDescription("The total size of the serialized data held by the block buffer (in bytes)");
        buffer_totBytesGauge = metrics.getOrCreate(bufferTotBytes);

        final LongGauge.Config numBlocksBuffered = newLongGauge(GROUP_BUFFER, "numBlocks")
                .withDescription("The number of blocks currently held in the block buffer");
        buffer_numBlocksGauge = metrics.getOrCreate(numBlocksBuffered);

        final LongGauge.Config numBlocksPendingAck = newLongGauge(GROUP_BUFFER, "numBlocksPendingAck")
                .withDescription("The number of blocks buffered that are pending acknowledgement");
        buffer_numBlocksPendingAckGauge = metrics.getOrCreate(numBlocksPendingAck);

        final DoubleGauge.Config unackedBlocksSaturation = newDoubleGauge(GROUP_BUFFER, "unackedBlocksSaturation")
                .withDescription("The percent (0.0 to 100.0) of buffered blocks that haven't been acknowledged");
        buffer_unackedBlocksSaturationGauge = metrics.getOrCreate(unackedBlocksSaturation);

        final DoubleGauge.Config unackedBytesSaturation = newDoubleGauge(GROUP_BUFFER, "unackedBytesSaturation")
                .withDescription("The percent (0.0 to 100.0) of buffered bytes that haven't been acknowledged");
        buffer_unackedBytesSaturationGauge = metrics.getOrCreate(unackedBytesSaturation);

        final DoubleGauge.Config inProgressBlocksSaturation = newDoubleGauge(GROUP_BUFFER, "inProgressBlocksSaturation")
                .withDescription("The percent (0.0 to 100.0) of blocks that have been opened but not yet closed");
        buffer_inProgressBlocksSaturationGauge = metrics.getOrCreate(inProgressBlocksSaturation);
    }

    private void registerWrappedRecordHashesMetrics() {
        final LongGauge.Config lowestCfg = newLongGauge(GROUP_RECORD_HASHES, "lowestBlock")
                .withDescription("Lowest record block number present in wrapped record hashes file (-1 if empty)");
        recordHashes_lowestBlockGauge = metrics.getOrCreate(lowestCfg);

        final LongGauge.Config highestCfg = newLongGauge(GROUP_RECORD_HASHES, "highestBlock")
                .withDescription("Highest record block number present in wrapped record hashes file (-1 if empty)");
        recordHashes_highestBlockGauge = metrics.getOrCreate(highestCfg);

        final LongGauge.Config hasGapsCfg = newLongGauge(GROUP_RECORD_HASHES, "hasGaps")
                .withDescription("Whether there are gaps between lowest and highest (0=no gaps, 1=gaps)");
        recordHashes_hasGapsGauge = metrics.getOrCreate(hasGapsCfg);
    }

    public void recordWrappedRecordHashesLowestBlock(final long blockNumber) {
        recordHashes_lowestBlockGauge.set(blockNumber);
    }

    public void recordWrappedRecordHashesHighestBlock(final long blockNumber) {
        recordHashes_highestBlockGauge.set(blockNumber);
    }

    public void recordWrappedRecordHashesHasGaps(final boolean hasGaps) {
        recordHashes_hasGapsGauge.set(hasGaps ? 1 : 0);
    }

    /**
     * Record the saturation percentage for in-progress blocks in the buffer.
     *
     * @param saturationPercent the saturation as a percentage
     */
    public void recordInProgressBlocksSaturation(final double saturationPercent) {
        buffer_inProgressBlocksSaturationGauge.set(saturationPercent);
    }

    /**
     * Record the saturation percentage for unacknowledged blocks buffered.
     *
     * @param saturationPercent the saturation as a percentage
     */
    public void recordUnackedBlocksSaturation(final double saturationPercent) {
        buffer_unackedBlocksSaturationGauge.set(saturationPercent);
    }

    /**
     * Record the saturation percentage for unacknowledged block bytes buffered.
     *
     * @param saturationPercent the saturation as a percentage
     */
    public void recordUnackedBytesSaturation(final double saturationPercent) {
        buffer_unackedBytesSaturationGauge.set(saturationPercent);
    }

    /**
     * Record the number of buffered blocks currently pending acknowledgements.
     *
     * @param numBlocks the number of blocks
     */
    public void recordBufferedBlocksPendingAck(final int numBlocks) {
        buffer_numBlocksPendingAckGauge.set(numBlocks);
    }

    /**
     * Record the number of blocks currently held by the block buffer.
     *
     * @param numBlocks the number of blocks
     */
    public void recordBufferedBlocks(final int numBlocks) {
        buffer_numBlocksGauge.set(numBlocks);
    }

    /**
     * Record the size of the block buffer (in bytes).
     *
     * @param bytes the total number of bytes consumed by the block buffer
     */
    public void recordBufferSizeInBytes(final long bytes) {
        buffer_totBytesGauge.set(bytes);
    }

    /**
     * Record the oldest block number in the buffer (after pruning).
     *
     * @param blockNumber the oldest block number
     */
    public void recordBufferOldestBlock(final long blockNumber) {
        buffer_oldestBlockGauge.set(blockNumber);
    }

    /**
     * Record the newest block number in the buffer (after pruning).
     *
     * @param blockNumber the newest block number
     */
    public void recordBufferNewestBlock(final long blockNumber) {
        buffer_newestBlockGauge.set(blockNumber);
    }

    /**
     * Record the current saturation of the block buffer.
     * @param saturation the current saturation (0.0 to 100.0)
     */
    public void recordBufferSaturation(final double saturation) {
        buffer_saturationGauge.set(saturation);
    }

    /**
     * Record the latest block number that was opened.
     * @param blockNumber the block number that was most recently opened
     */
    public void recordLatestBlockOpened(final long blockNumber) {
        buffer_latestBlockOpenedGauge.set(blockNumber);
    }

    /**
     * Record the highest block number that was acknowledged.
     * @param blockNumber the block number that was most recently acknowledged
     */
    public void recordLatestBlockAcked(final long blockNumber) {
        buffer_latestBlockAckedGauge.set(blockNumber);
    }

    /**
     * Record the number of blocks that were pruned in the latest pruning cycle.
     * @param numBlocksPruned the number of blocks that were pruned
     */
    public void recordNumberOfBlocksPruned(final int numBlocksPruned) {
        if (numBlocksPruned > 0) {
            buffer_numBlocksPrunedCounter.add(numBlocksPruned);
        }
    }

    /**
     * Record that a block was opened.
     */
    public void recordBlockOpened() {
        buffer_numBlocksOpenedCounter.increment();
    }

    /**
     * Record that a block was closed.
     */
    public void recordBlockClosed() {
        buffer_numBlocksClosedCounter.increment();
    }

    /**
     * Record that an attempt to retrieve a block from the block buffer failed because the block was missing.
     */
    public void recordBlockMissing() {
        buffer_numBlocksMissingCounter.increment();
    }

    /**
     * Record that back pressure is active.
     */
    public void recordBackPressureActive() {
        buffer_backPressureStateGauge.set(BACK_PRESSURE_ACTIVE);
    }

    /**
     * Record that back pressure is at the action stage.
     */
    public void recordBackPressureActionStage() {
        buffer_backPressureStateGauge.set(BACK_PRESSURE_ACTION_STAGE);
    }

    /**
     * Record that back pressure is recovering.
     */
    public void recordBackPressureRecovering() {
        buffer_backPressureStateGauge.set(BACK_PRESSURE_RECOVERING);
    }

    /**
     * Record that back pressure is disabled (normal state).
     */
    public void recordBackPressureDisabled() {
        buffer_backPressureStateGauge.set(BACK_PRESSURE_DISABLED);
    }

    // Connectivity metrics --------------------------------------------------------------------------------------------

    private void registerConnectivityMetrics() {
        for (final ConnectionType connectionType : ConnectionType.values()) {
            final String groupName = groupName(connectionType, GROUP_CONN);
            final Counter.Config onCompleteCfg = newCounter(groupName, "onComplete")
                    .withDescription("Number of onComplete handler invocations on block node "
                            + connectionType.shortName() + " connections");
            conn_onCompleteCounters.put(connectionType, metrics.getOrCreate(onCompleteCfg));

            final Counter.Config onErrorCfg = newCounter(groupName, "onError")
                    .withDescription("Number of onError handler invocations on block node " + connectionType.shortName()
                            + " connections");
            conn_onErrorCounters.put(connectionType, metrics.getOrCreate(onErrorCfg));

            final Counter.Config openedCfg = newCounter(groupName, "opened")
                    .withDescription("Number of block node " + connectionType.shortName() + " connections opened");
            conn_openedCounters.put(connectionType, metrics.getOrCreate(openedCfg));

            final Counter.Config closedCfg = newCounter(groupName, "closed")
                    .withDescription("Number of block node " + connectionType.shortName() + " connections closed");
            conn_closedCounters.put(connectionType, metrics.getOrCreate(closedCfg));

            final Counter.Config createFailureCfg = newCounter(groupName, "createFailure")
                    .withDescription("Number of times establishing a block node " + connectionType.shortName()
                            + " connection failed");
            conn_createFailureCounters.put(connectionType, metrics.getOrCreate(createFailureCfg));

            final Counter.Config timeoutCfg = newCounter(groupName, "operationTimeout")
                    .withDescription("Number of times an operation timed out on a block node "
                            + connectionType.shortName() + " connection");
            conn_operationTimeoutCounters.put(connectionType, metrics.getOrCreate(timeoutCfg));

            final Map<CloseReason, Counter> closeCounters = new EnumMap<>(CloseReason.class);
            conn_closeReasonCounters.put(connectionType, closeCounters);

            for (final CloseReason closeReason : CloseReason.values()) {
                final String metricName = "connClose_" + toCamelCase(closeReason.name());
                final Counter.Config cfg = newCounter(groupName, metricName)
                        .withDescription("Number of " + connectionType.shortName() + " connections closed with reason "
                                + closeReason.name());
                closeCounters.put(closeReason, metrics.getOrCreate(cfg));
            }
        }

        final LongGauge.Config activeConnIpCfg = newLongGauge(GROUP_CONN, "activeConnIp")
                .withDescription(
                        "IP address (in integer format) of the currently active streaming block node connection");
        conn_activeConnIpGauge = metrics.getOrCreate(activeConnIpCfg);

        final Counter.Config endOfStreamLimitCfg = newCounter(GROUP_CONN, "endOfStreamLimitExceeded")
                .withDescription(
                        "Number of times the active block node connection has exceeded the allowed number of EndOfStream responses");
        conn_endOfStreamLimitCounter = metrics.getOrCreate(endOfStreamLimitCfg);

        final Counter.Config highLatencyCfg = newCounter(GROUP_CONN, "highLatencyEvents")
                .withDescription("Count of high latency events from the active block node connection");
        conn_highLatencyCounter = metrics.getOrCreate(highLatencyCfg);

        final RunningAverageMetric.Config headerAckLatencyCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN + "_headerSentToAckLatency")
                .withDescription(
                        "The average latency (µs) between streaming a BlockHeader and receiving its BlockAcknowledgement")
                .withFormat("%,.2f");
        conn_headerSentToAckLatency = metrics.getOrCreate(headerAckLatencyCfg);

        final RunningAverageMetric.Config headerProducedAckLatencyCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN + "_headerProducedToAckLatency")
                .withDescription(
                        "The average latency (µs) between producing a BlockHeader and receiving its BlockAcknowledgement")
                .withFormat("%,.2f");
        conn_headerProducedToAckLatency = metrics.getOrCreate(headerProducedAckLatencyCfg);

        final RunningAverageMetric.Config blockEndSentAckLatencyCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN + "_blockEndSentToAckLatency")
                .withDescription(
                        "The average latency (µs) between streaming a BlockEnd and receiving its BlockAcknowledgement")
                .withFormat("%,.2f");
        conn_blockEndSentToAckLatency = metrics.getOrCreate(blockEndSentAckLatencyCfg);

        final RunningAverageMetric.Config blockClosedAckLatencyCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN + "_blockClosedToAckLatency")
                .withDescription(
                        "The average latency (µs) between the block being complete and receiving its BlockAcknowledgement")
                .withFormat("%,.2f");
        conn_blockClosedToAckLatency = metrics.getOrCreate(blockClosedAckLatencyCfg);

        final RunningAverageMetric.Config headerToBlockEndLatencyCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN + "_headerSentToBlockEndSentLatency")
                .withDescription(
                        "The average latency (µs) between streaming a BlockHeader and streaming the corresponding BlockEnd")
                .withFormat("%,.2f");

        conn_headerSentToBlockEndSentLatency = metrics.getOrCreate(headerToBlockEndLatencyCfg);

        final LongGauge.Config activeStreamingConnectionCountCfg = new LongGauge.Config(
                        CATEGORY, GROUP_CONN + "_activeStreamingConnectionCount")
                .withDescription("Current number of active streaming connections to block nodes");
        conn_activeStreamingConnectionCount = metrics.getOrCreate(activeStreamingConnectionCountCfg);

        final LongGauge.Config activeObservingConnectionCountCfg = new LongGauge.Config(
                        CATEGORY, GROUP_CONN + "_activeObservingConnectionCount")
                .withDescription("Current number of active acknowledgement observing connections to block nodes");
        conn_activeObservingConnectionCount = metrics.getOrCreate(activeObservingConnectionCountCfg);
    }

    /**
     * Record the number of active connections that are streaming to block nodes.
     *
     * @param connectionCount the latest connection count
     */
    public void recordActiveStreamingConnectionCount(final long connectionCount) {
        conn_activeStreamingConnectionCount.set(connectionCount);
    }

    /**
     * Record the number of active connections that are observing acknowledgements from block nodes.
     *
     * @param connectionCount the latest connection count
     */
    public void recordActiveObservingConnectionCount(final long connectionCount) {
        conn_activeObservingConnectionCount.set(connectionCount);
    }

    /**
     * Record that an active connection has exceeded the allowed number of EndOfStream responses.
     */
    public void recordEndOfStreamLimitExceeded() {
        conn_endOfStreamLimitCounter.increment();
    }

    /**
     * Record that a connection to a block node completed normally.
     * @param connectionType the type of connection that invoked onComplete
     */
    public void recordConnectionOnComplete(@NonNull final ConnectionType connectionType) {
        requireNonNull(connectionType, "Connection type is required");
        conn_onCompleteCounters.get(connectionType).increment();
    }

    /**
     * Record that an error occurred on a connection to a block node.
     * @param connectionType the type of connection that invoked onError
     */
    public void recordConnectionOnError(@NonNull final ConnectionType connectionType) {
        requireNonNull(connectionType, "Connection type is required");
        conn_onErrorCounters.get(connectionType).increment();
    }

    /**
     * Record that a streaming connection to a block node was opened.
     * @param connectionType the type of connection opened
     */
    public void recordConnectionOpened(@NonNull final ConnectionType connectionType) {
        requireNonNull(connectionType, "Connection type is required");
        conn_openedCounters.get(connectionType).increment();
    }

    /**
     * Record that a streaming connection to a block node was closed.
     * @param connectionType the type of connection being closed
     * @param closeReason the reason why the connection was closed
     */
    public void recordConnectionClosed(
            @NonNull final ConnectionType connectionType, @NonNull final CloseReason closeReason) {
        requireNonNull(connectionType, "Connection type is required");
        requireNonNull(closeReason, "Close reason is required");
        conn_closedCounters.get(connectionType).increment();
        conn_closeReasonCounters.get(connectionType).get(closeReason).increment();
    }

    /**
     * Record that establishing a streaming connection to a block node failed.
     * @param connectionType the type of connection that failed creation
     */
    public void recordConnectionCreateFailure(@NonNull final ConnectionType connectionType) {
        requireNonNull(connectionType, "Connection type is required");
        conn_createFailureCounters.get(connectionType).increment();
    }

    /**
     * Records the specified IP address (in integer form) as the current active connection.
     *
     * @param ipAddress address of the current active connection
     */
    public void recordActiveConnectionIp(final long ipAddress) {
        conn_activeConnIpGauge.set(ipAddress);
    }

    /**
     * Record a high-latency event for a specific block node.
     */
    public void recordHighLatencyEvent() {
        conn_highLatencyCounter.increment();
    }

    /**
     * Record the latency between sending a block header and receiving the corresponding acknowledgement.
     * @param latencyMicros the latency in microseconds
     */
    public void recordHeaderSentAckLatency(final long latencyMicros) {
        conn_headerSentToAckLatency.update(latencyMicros);
    }

    /**
     * Record the latency between producing a block header (added to block state) and receiving the acknowledgement.
     * @param latencyMicros the latency in microseconds
     */
    public void recordHeaderProducedToAckLatency(final long latencyMicros) {
        conn_headerProducedToAckLatency.update(latencyMicros);
    }

    /**
     * Record the latency between sending a block header and sending the corresponding BlockEnd.
     * @param latencyMicros the latency in microseconds
     */
    public void recordHeaderSentToBlockEndSentLatency(final long latencyMicros) {
        conn_headerSentToBlockEndSentLatency.update(latencyMicros);
    }

    /**
     * Record the latency between sending EndOfBlock and receiving the acknowledgement.
     * @param latencyMicros the latency in microseconds
     */
    public void recordBlockEndSentToAckLatency(final long latencyMicros) {
        conn_blockEndSentToAckLatency.update(latencyMicros);
    }

    /**
     * Record the latency between the BlockState closed timestamp and receiving the acknowledgement.
     * @param latencyMicros the latency in microseconds
     */
    public void recordBlockClosedToAckLatency(final long latencyMicros) {
        conn_blockClosedToAckLatency.update(latencyMicros);
    }

    /**
     * Record that a pipeline onNext() or onComplete() operation timed out.
     * @param connectionType the type of connection where the timeout occurred
     */
    public void recordOperationTimeout(@NonNull final ConnectionType connectionType) {
        requireNonNull(connectionType, "Connection type is required");
        conn_operationTimeoutCounters.get(connectionType).increment();
    }

    // Connection RECV metrics -----------------------------------------------------------------------------------------

    private void registerConnectionRecvMetrics() {
        for (final ConnectionType connectionType : ConnectionType.values()) {
            final String connectionTypeName = toCamelCase(connectionType.shortName());
            final String groupName = groupName(connectionType, GROUP_CONN_RECV);
            final Counter.Config recvUnknownCfg = newCounter(groupName, "unknown")
                    .withDescription("Number of unknown response types received on block node "
                            + connectionType.shortName() + " connections");
            connRecv_unknownCounters.put(connectionType, metrics.getOrCreate(recvUnknownCfg));

            final Map<ResponseOneOfType, Counter> responseCounters = new EnumMap<>(ResponseOneOfType.class);
            connRecv_counters.put(connectionType, responseCounters);
            final Map<EndOfStream.Code, Counter> eosCounters = new EnumMap<>(EndOfStream.Code.class);
            connRecv_endOfStreamCounters.put(connectionType, eosCounters);

            for (final ResponseOneOfType responseType : ResponseOneOfType.values()) {
                final String respName = toCamelCase(responseType.protoName());

                switch (responseType) {
                    case UNSET -> {
                        /* ignore */
                    }
                    case END_STREAM -> {
                        for (final EndOfStream.Code eosCode : EndOfStream.Code.values()) {
                            final String name = respName + "_" + toCamelCase(eosCode.protoName());
                            final Counter.Config cfg = newCounter(groupName, name)
                                    .withDescription("Number of 'EndOfStream." + eosCode.protoName()
                                            + "' responses received on block node " + connectionTypeName
                                            + " connections");
                            eosCounters.put(eosCode, metrics.getOrCreate(cfg));
                        }
                    }
                    default -> {
                        final Counter.Config cfg = newCounter(groupName, respName)
                                .withDescription("Number of '" + toPascalCase(responseType.name())
                                        + "' responses received on block node " + connectionTypeName + " connections");
                        responseCounters.put(responseType, metrics.getOrCreate(cfg));
                    }
                }
            }
        }

        final LongGauge.Config latestBlockEosCfg = newLongGauge(GROUP_CONN_RECV, "latestBlockEndOfStream")
                .withDescription("The latest block number received in an EndOfStream response");
        this.connRecv_latestBlockEndOfStreamGauge = metrics.getOrCreate(latestBlockEosCfg);

        final LongGauge.Config latestBlockSkipCfg = newLongGauge(GROUP_CONN_RECV, "latestBlockSkipBlock")
                .withDescription("The latest block number received in a SkipBlock response");
        this.connRecv_latestBlockSkipBlockGauge = metrics.getOrCreate(latestBlockSkipCfg);

        final LongGauge.Config latestBlockResendCfg = newLongGauge(GROUP_CONN_RECV, "latestBlockResendBlock")
                .withDescription("The latest block number received in a ResendBlock response");
        this.connRecv_latestBlockResendBlockGauge = metrics.getOrCreate(latestBlockResendCfg);

        final LongGauge.Config latestBlockBehindCfg = newLongGauge(GROUP_CONN_RECV, "latestBlockBehindPublisher")
                .withDescription("The latest block number received in a BehindPublisher response");
        this.connRecv_latestBlockBehindPublisherGauge = metrics.getOrCreate(latestBlockBehindCfg);

        final Counter.Config observerIgnoredCfg = newCounter(GROUP_CONN_RECV, "responsesIgnoredAsObserving")
                .withDescription("The number of ignored responses from the block node over observing connections");
        this.connRecv_observerIgnored = metrics.getOrCreate(observerIgnoredCfg);
    }

    /**
     * Record that an unknown response was received from a block node.
     * @param connectionType the type of connection that received the response
     */
    public void recordUnknownResponseReceived(@NonNull final ConnectionType connectionType) {
        requireNonNull(connectionType, "Connection type is required");
        connRecv_unknownCounters.get(connectionType).increment();
    }

    /**
     * Record that a response was received from a block node.
     * @param connectionType the type of connection that received the response
     * @param responseType the type of response received
     */
    public void recordResponseReceived(
            @NonNull final ConnectionType connectionType, @NonNull final ResponseOneOfType responseType) {
        requireNonNull(connectionType, "Connection type is required");
        requireNonNull(responseType, "Response type is required");
        connRecv_counters.get(connectionType).get(responseType).increment();
    }

    /**
     * Record the size (in bytes) of a Block.
     * @param numBytes the size of the Block in bytes
     */
    public void recordBlockBytes(final long numBytes) {
        buffer_blockBytes.update(numBytes);
    }

    /**
     * Record that an end of stream response was received from a block node.
     * @param connectionType the type of connection that received the response
     * @param responseType the type of end of stream response received
     */
    public void recordResponseEndOfStreamReceived(
            @NonNull final ConnectionType connectionType, @NonNull final EndOfStream.Code responseType) {
        requireNonNull(connectionType, "Connection type is required");
        requireNonNull(responseType, "Response type is required");
        connRecv_endOfStreamCounters.get(connectionType).get(responseType).increment();
    }

    /**
     * Record the latest block number received in an EndOfStream response.
     * @param blockNumber the block number from the response
     */
    public void recordLatestBlockEndOfStream(final long blockNumber) {
        connRecv_latestBlockEndOfStreamGauge.set(blockNumber);
    }

    /**
     * Record the latest block number received in a SkipBlock response.
     * @param blockNumber the block number from the response
     */
    public void recordLatestBlockSkipBlock(final long blockNumber) {
        connRecv_latestBlockSkipBlockGauge.set(blockNumber);
    }

    /**
     * Record the latest block number received in a ResendBlock response.
     * @param blockNumber the block number from the response
     */
    public void recordLatestBlockResendBlock(final long blockNumber) {
        connRecv_latestBlockResendBlockGauge.set(blockNumber);
    }

    /**
     * Record the latest block number received in a BehindPublisher response.
     * @param blockNumber the block number from the response
     */
    public void recordLatestBlockBehindPublisher(final long blockNumber) {
        connRecv_latestBlockBehindPublisherGauge.set(blockNumber);
    }

    /**
     * Record a response from the block node as being ignored because it was not valid for an observing connection.
     */
    public void recordIgnoredResponseWhileObserving() {
        connRecv_observerIgnored.increment();
    }

    // Connection SEND metrics -----------------------------------------------------------------------------------------

    private void registerConnectionSendMetrics() {
        for (final ConnectionType connectionType : ConnectionType.values()) {
            final String connectionTypeName = toCamelCase(connectionType.shortName());
            final String groupName = groupName(connectionType, GROUP_CONN_SEND);
            final Counter.Config failureCfg = newCounter(groupName, "failure")
                    .withDescription("Number of failed requests attempted using block node "
                            + connectionType.shortName() + " connections");
            connSend_failureCounters.put(connectionType, metrics.getOrCreate(failureCfg));

            final RunningAverageMetric.Config reqLatencyCfg = newRunningAverage(groupName, "requestLatency")
                    .withDescription("Average latency (µs) for a request to be sent on block node " + connectionTypeName
                            + " connections");
            connSend_requestLatencyMetrics.put(connectionType, metrics.getOrCreate(reqLatencyCfg));

            final RunningAverageMetric.Config reqBytesCfg = newRunningAverage(groupName, "requestBytes")
                    .withDescription("Average number of bytes in a request sent on block node " + connectionTypeName
                            + " connections");
            connSend_RequestBytesMetrics.put(connectionType, metrics.getOrCreate(reqBytesCfg));

            final Map<RequestOneOfType, Counter> requestCounters = new EnumMap<>(RequestOneOfType.class);
            connSend_counters.put(connectionType, requestCounters);
            final Map<EndStream.Code, Counter> esCounters = new EnumMap<>(EndStream.Code.class);
            connSend_endStreamCounters.put(connectionType, esCounters);

            for (final RequestOneOfType requestType : RequestOneOfType.values()) {
                final String reqName = toCamelCase(requestType.protoName());

                switch (requestType) {
                    case UNSET -> {
                        /* ignore */
                    }
                    case END_STREAM -> {
                        for (final EndStream.Code esCode : EndStream.Code.values()) {
                            final String name = reqName + "_" + toCamelCase(esCode.protoName());
                            final Counter.Config cfg = newCounter(groupName, name)
                                    .withDescription("Number of 'EndStream." + esCode.protoName()
                                            + "' requests sent on block node " + connectionTypeName + " connections");
                            esCounters.put(esCode, metrics.getOrCreate(cfg));
                        }
                    }
                    default -> {
                        final Counter.Config cfg = newCounter(groupName, reqName)
                                .withDescription("Number of '" + toPascalCase(requestType.name())
                                        + "' requests sent on block node " + connectionTypeName + " connections");
                        requestCounters.put(requestType, metrics.getOrCreate(cfg));
                    }
                }
            }
        }

        final Counter.Config blockItemsCfg = newCounter(GROUP_CONN_SEND, "blockItemCount")
                .withDescription("Number of individual block items sent to block nodes");
        this.connSend_blockItemsCounter = metrics.getOrCreate(blockItemsCfg);

        final Counter.Config multiItemReqExceedsSoftLimitCfg = newCounter(
                        GROUP_CONN_SEND, "multiItemRequestExceedsSoftLimit")
                .withDescription(
                        "Number of requests that contain multiple items whose total size exceeds the soft limit size");
        this.connSend_multiItemRequestExceedsSoftLimitCounter = metrics.getOrCreate(multiItemReqExceedsSoftLimitCfg);

        final Counter.Config requestExceedsHardLimitCfg = newCounter(GROUP_CONN_SEND, "requestExceedsHardLimit")
                .withDescription("Number of requests that exceed the hard limit size");
        this.connSend_requestExceedsHardLimitCounter = metrics.getOrCreate(requestExceedsHardLimitCfg);

        final LongGauge.Config latestBlockEndOfBlockCfg = newLongGauge(GROUP_CONN_SEND, "latestBlockEndOfBlock")
                .withDescription("The latest block number for which an EndOfBlock request was sent");
        this.connSend_latestBlockEndOfBlockGauge = metrics.getOrCreate(latestBlockEndOfBlockCfg);

        final LongGauge.Config streamingBlockCfg = newLongGauge(GROUP_CONN_SEND, "streamingBlockNumber")
                .withDescription("The current block number this connection is streaming");
        connSend_streamingBlockNumberGauge = metrics.getOrCreate(streamingBlockCfg);

        final RunningAverageMetric.Config requestBlockItemCountCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN_SEND + "_requestBlockItemCount")
                .withDescription("The average number of BlockItems in a PublishStreamRequest")
                .withFormat("%,.2f");
        connSend_requestBlockItemCount = metrics.getOrCreate(requestBlockItemCountCfg);
    }

    /**
     * Record that a request was sent to a block node.
     * @param connectionType the type of connection sending the request
     * @param requestType the type of request sent
     */
    public void recordRequestSent(
            @NonNull final ConnectionType connectionType, @NonNull final RequestOneOfType requestType) {
        requireNonNull(connectionType, "Connection type is required");
        requireNonNull(requestType, "Request type is required");
        connSend_counters.get(connectionType).get(requestType).increment();
    }

    /**
     * Record the number of block items sent in a request to a block node.
     * @param numBlockItems the number of block items sent in the request
     */
    public void recordBlockItemsSent(final int numBlockItems) {
        if (numBlockItems > 0) {
            connSend_blockItemsCounter.add(numBlockItems);
        }
    }

    /**
     * Record that an end stream request was sent to a block node.
     * @param connectionType the type of connection sending the EndStream request
     * @param endStreamCode the type of end stream request sent
     */
    public void recordRequestEndStreamSent(
            @NonNull final ConnectionType connectionType, @NonNull final EndStream.Code endStreamCode) {
        requireNonNull(connectionType, "Connection type is required");
        requireNonNull(endStreamCode, "End stream code is required");
        connSend_endStreamCounters.get(connectionType).get(endStreamCode).increment();
    }

    /**
     * Record the latency for a request to be sent to a block node.
     * @param connectionType the type of connection sending the EndStream request
     * @param latencyMicros the latency in microseconds
     */
    public void recordRequestLatency(@NonNull final ConnectionType connectionType, final long latencyMicros) {
        requireNonNull(connectionType, "Connection type is required");
        connSend_requestLatencyMetrics.get(connectionType).update(latencyMicros);
    }

    /**
     * Record that a request to a block node failed to be sent.
     */
    public void recordRequestSendFailure(@NonNull final ConnectionType connectionType) {
        requireNonNull(connectionType, "Connection type is required");
        connSend_failureCounters.get(connectionType).increment();
    }

    /**
     * Record the latest block number for which an EndOfBlock request was sent.
     * @param blockNumber the block number
     */
    public void recordLatestBlockEndOfBlockSent(final long blockNumber) {
        connSend_latestBlockEndOfBlockGauge.set(blockNumber);
    }

    /**
     * Record the current block number being streamed on the active connection.
     * @param blockNumber the block number now being streamed
     */
    public void recordStreamingBlockNumber(final long blockNumber) {
        connSend_streamingBlockNumberGauge.set(blockNumber);
    }

    /**
     * Record the number of BlockItems contained in a completed block.
     * @param numBlockItems the number of BlockItems in the block
     */
    public void recordBlockItemsPerBlock(final int numBlockItems) {
        buffer_blockItemsPerBlock.update(numBlockItems);
    }

    /**
     * Record the size (in bytes) of a streamed BlockItem.
     * @param numBytes the size of the BlockItem in bytes
     */
    public void recordBlockItemBytes(final long numBytes) {
        buffer_blockItemBytes.update(numBytes);
    }

    /**
     * Record the size (in bytes) of a PublishStreamRequest that was sent.
     * @param connectionType the type of connection sending the EndStream request
     * @param numBytes the size of the request in bytes
     */
    public void recordRequestBytes(@NonNull final ConnectionType connectionType, final long numBytes) {
        requireNonNull(connectionType, "Connection type is required");
        connSend_RequestBytesMetrics.get(connectionType).update(numBytes);
    }

    /**
     * Record the number of BlockItems included in a PublishStreamRequest.
     * @param numBlockItems the number of BlockItems in the request
     */
    public void recordRequestBlockItemCount(final int numBlockItems) {
        connSend_requestBlockItemCount.update(numBlockItems);
    }

    /**
     * Record that a pending request with multiple items exceeds the configured soft limit size.
     */
    public void recordMultiItemRequestExceedsSoftLimit() {
        connSend_multiItemRequestExceedsSoftLimitCounter.increment();
    }

    /**
     * Record that a pending request has exceeded the hard limit size.
     */
    public void recordRequestExceedsHardLimit() {
        connSend_requestExceedsHardLimitCounter.increment();
    }

    // Utilities -------------------------------------------------------------------------------------------------------

    private static String toCamelCase(final String in) {
        // FOO_BAR -> foo_bar -> fooBar
        final StringBuilder sb = new StringBuilder(in.toLowerCase());
        int index = sb.indexOf("_");
        while (index != -1) {
            if (index == (sb.length() - 1)) {
                // underscore was the last character
                sb.deleteCharAt(index);
            } else {
                // we found an underscore and there is a character after it
                char nextChar = sb.charAt(index + 1);
                nextChar = Character.toUpperCase(nextChar);
                sb.deleteCharAt(index + 1);
                sb.replace(index, index + 1, nextChar + "");
            }

            index = sb.indexOf("_");
        }

        return sb.toString();
    }

    private static String groupName(final ConnectionType connectionType, final String group) {
        final String typeName = toCamelCase(connectionType.shortName());
        return typeName + capitalizeFirstLetter(group);
    }

    private static String capitalizeFirstLetter(final String string) {
        final char firstChar = Character.toUpperCase(string.charAt(0));
        return firstChar + string.substring(1);
    }

    private static String toPascalCase(final String in) {
        // FOO_BAR -> FooBar
        final String camelCase = toCamelCase(in);
        return capitalizeFirstLetter(camelCase);
    }

    private RunningAverageMetric.Config newRunningAverage(final String group, final String metric) {
        final String metricName = group + "_" + metric;
        return new RunningAverageMetric.Config(CATEGORY, metricName);
    }

    private Counter.Config newCounter(final String group, final String metric) {
        final String metricName = group + "_" + metric;
        return new Counter.Config(CATEGORY, metricName);
    }

    private DoubleGauge.Config newDoubleGauge(final String group, final String metric) {
        final String metricName = group + "_" + metric;
        return new DoubleGauge.Config(CATEGORY, metricName);
    }

    private LongGauge.Config newLongGauge(final String group, final String metric) {
        final String metricName = group + "_" + metric;
        return new LongGauge.Config(CATEGORY, metricName);
    }
}
