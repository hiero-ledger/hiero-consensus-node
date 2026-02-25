// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.metrics;

import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamResponse;
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
    private Counter connSend_failureCounter;
    private Counter connSend_blockItemsCounter;
    private RunningAverageMetric connSend_publishStreamRequestLatency;
    private Counter connSend_multiItemRequestExceedsSoftLimitCounter;
    private Counter connSend_requestExceedsHardLimitCounter;
    private LongGauge connSend_latestBlockEndOfBlockGauge;
    private LongGauge connSend_streamingBlockNumberGauge;
    private RunningAverageMetric buffer_blockItemsPerBlock;
    private RunningAverageMetric buffer_blockItemBytes;
    private RunningAverageMetric buffer_blockBytes;
    private RunningAverageMetric connSend_requestBytes;
    private RunningAverageMetric connSend_requestBlockItemCount;
    private final Map<PublishStreamRequest.RequestOneOfType, Counter> connSend_counters =
            new EnumMap<>(PublishStreamRequest.RequestOneOfType.class);
    private final Map<PublishStreamRequest.EndStream.Code, Counter> connSend_endStreamCounters =
            new EnumMap<>(PublishStreamRequest.EndStream.Code.class);

    // connection receive metrics
    private final Map<PublishStreamResponse.EndOfStream.Code, Counter> connRecv_endOfStreamCounters =
            new EnumMap<>(PublishStreamResponse.EndOfStream.Code.class);
    private final Map<PublishStreamResponse.ResponseOneOfType, Counter> connRecv_counters =
            new EnumMap<>(PublishStreamResponse.ResponseOneOfType.class);
    private Counter connRecv_unknownCounter;
    private LongGauge connRecv_latestBlockEndOfStreamGauge;
    private LongGauge connRecv_latestBlockSkipBlockGauge;
    private LongGauge connRecv_latestBlockResendBlockGauge;
    private LongGauge connRecv_latestBlockBehindPublisherGauge;

    // connectivity metrics
    private Counter conn_onCompleteCounter;
    private Counter conn_onErrorCounter;
    private Counter conn_openedCounter;
    private Counter conn_closedCounter;
    private Counter conn_createFailureCounter;
    private LongGauge conn_activeConnIpGauge;
    private Counter conn_endOfStreamLimitCounter;
    private Counter conn_highLatencyCounter;
    private Counter conn_pipelineOperationTimeoutCounter;
    private RunningAverageMetric conn_headerSentToAckLatency;
    private RunningAverageMetric conn_headerProducedToAckLatency;
    private RunningAverageMetric conn_blockEndSentToAckLatency;
    private RunningAverageMetric conn_blockClosedToAckLatency;
    private RunningAverageMetric conn_headerSentToBlockEndSentLatency;

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
                .withDescription("The percent (0.0 to 100.0) of buffered blocks that haven't been acknowledged");
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
        final Counter.Config onCompleteCfg = newCounter(GROUP_CONN, "onComplete")
                .withDescription("Number of onComplete handler invocations on block node connections");
        conn_onCompleteCounter = metrics.getOrCreate(onCompleteCfg);

        final Counter.Config onErrorCfg = newCounter(GROUP_CONN, "onError")
                .withDescription("Number of onError handler invocations on block node connections");
        conn_onErrorCounter = metrics.getOrCreate(onErrorCfg);

        final Counter.Config openedCfg =
                newCounter(GROUP_CONN, "opened").withDescription("Number of block node connections opened");
        conn_openedCounter = metrics.getOrCreate(openedCfg);

        final Counter.Config closedCfg =
                newCounter(GROUP_CONN, "closed").withDescription("Number of block node connections closed");
        conn_closedCounter = metrics.getOrCreate(closedCfg);

        final Counter.Config createFailureCfg = newCounter(GROUP_CONN, "createFailure")
                .withDescription("Number of times establishing a block node connection failed");
        conn_createFailureCounter = metrics.getOrCreate(createFailureCfg);

        final LongGauge.Config activeConnIpCfg = newLongGauge(GROUP_CONN, "activeConnIp")
                .withDescription("IP address (in integer format) of the currently active block node connection");
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
                        "The average latency (ms) between streaming a BlockHeader and receiving its BlockAcknowledgement")
                .withFormat("%,.2f");
        conn_headerSentToAckLatency = metrics.getOrCreate(headerAckLatencyCfg);

        final RunningAverageMetric.Config headerProducedAckLatencyCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN + "_headerProducedToAckLatency")
                .withDescription(
                        "The average latency (ms) between producing a BlockHeader and receiving its BlockAcknowledgement")
                .withFormat("%,.2f");
        conn_headerProducedToAckLatency = metrics.getOrCreate(headerProducedAckLatencyCfg);

        final RunningAverageMetric.Config blockEndSentAckLatencyCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN + "_blockEndSentToAckLatency")
                .withDescription(
                        "The average latency (ms) between streaming a BlockEnd and receiving its BlockAcknowledgement")
                .withFormat("%,.2f");
        conn_blockEndSentToAckLatency = metrics.getOrCreate(blockEndSentAckLatencyCfg);

        final RunningAverageMetric.Config blockClosedAckLatencyCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN + "_blockClosedToAckLatency")
                .withDescription(
                        "The average latency (ms) between the block being complete and receiving its BlockAcknowledgement")
                .withFormat("%,.2f");
        conn_blockClosedToAckLatency = metrics.getOrCreate(blockClosedAckLatencyCfg);

        final RunningAverageMetric.Config headerToBlockEndLatencyCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN + "_headerSentToBlockEndSentLatency")
                .withDescription(
                        "The average latency (ms) between streaming a BlockHeader and streaming the corresponding BlockEnd")
                .withFormat("%,.2f");

        conn_headerSentToBlockEndSentLatency = metrics.getOrCreate(headerToBlockEndLatencyCfg);

        final Counter.Config pipelineTimeoutCfg = newCounter(GROUP_CONN, "pipelineOperationTimeout")
                .withDescription(
                        "Number of times a pipeline onNext() or onComplete() operation timed out on a block node connection");
        conn_pipelineOperationTimeoutCounter = metrics.getOrCreate(pipelineTimeoutCfg);
    }

    /**
     * Record that an active connection has exceeded the allowed number of EndOfStream responses.
     */
    public void recordEndOfStreamLimitExceeded() {
        conn_endOfStreamLimitCounter.increment();
    }

    /**
     * Record that a connection to a block node completed normally.
     */
    public void recordConnectionOnComplete() {
        conn_onCompleteCounter.increment();
    }

    /**
     * Record that an error occurred on a connection to a block node.
     */
    public void recordConnectionOnError() {
        conn_onErrorCounter.increment();
    }

    /**
     * Record that a connection to a block node was opened.
     */
    public void recordConnectionOpened() {
        conn_openedCounter.increment();
    }

    /**
     * Record that a connection to a block node was closed.
     */
    public void recordConnectionClosed() {
        conn_closedCounter.increment();
    }

    /**
     * Record that establishing a connection to a block node failed.
     */
    public void recordConnectionCreateFailure() {
        conn_createFailureCounter.increment();
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
     * @param latencyMs the latency in milliseconds
     */
    public void recordHeaderSentAckLatency(final long latencyMs) {
        conn_headerSentToAckLatency.update(latencyMs);
    }

    /**
     * Record the latency between producing a block header (added to block state) and receiving the acknowledgement.
     * @param latencyMs the latency in milliseconds
     */
    public void recordHeaderProducedToAckLatency(final long latencyMs) {
        conn_headerProducedToAckLatency.update(latencyMs);
    }

    /**
     * Record the latency between sending a block header and sending the corresponding BlockEnd.
     * @param latencyMs the latency in milliseconds
     */
    public void recordHeaderSentToBlockEndSentLatency(final long latencyMs) {
        conn_headerSentToBlockEndSentLatency.update(latencyMs);
    }

    /**
     * Record the latency between sending EndOfBlock and receiving the acknowledgement.
     * @param latencyMs the latency in milliseconds
     */
    public void recordBlockEndSentToAckLatency(final long latencyMs) {
        conn_blockEndSentToAckLatency.update(latencyMs);
    }

    /**
     * Record the latency between the BlockState closed timestamp and receiving the acknowledgement.
     * @param latencyMs the latency in milliseconds
     */
    public void recordBlockClosedToAckLatency(final long latencyMs) {
        conn_blockClosedToAckLatency.update(latencyMs);
    }

    /**
     * Record that a pipeline onNext() or onComplete() operation timed out.
     */
    public void recordPipelineOperationTimeout() {
        conn_pipelineOperationTimeoutCounter.increment();
    }

    // Connection RECV metrics -----------------------------------------------------------------------------------------

    private void registerConnectionRecvMetrics() {
        for (final PublishStreamResponse.ResponseOneOfType respType :
                PublishStreamResponse.ResponseOneOfType.values()) {
            final String respTypeName = toCamelCase(respType.protoName());
            switch (respType) {
                case UNSET -> {
                    /* ignore */
                }
                case END_STREAM -> {
                    final String namePrefix = respTypeName + "_";
                    for (final PublishStreamResponse.EndOfStream.Code eosCode :
                            PublishStreamResponse.EndOfStream.Code.values()) {
                        final String name = respTypeName + "_" + toCamelCase(eosCode.protoName());
                        final Counter.Config cfg = newCounter(
                                        GROUP_CONN_RECV, namePrefix + toCamelCase(eosCode.protoName()))
                                .withDescription("Number of " + name + " responses received from block nodes");
                        connRecv_endOfStreamCounters.put(eosCode, metrics.getOrCreate(cfg));
                    }
                }
                default -> {
                    final Counter.Config cfg = newCounter(GROUP_CONN_RECV, respTypeName)
                            .withDescription("Number of " + respTypeName + " responses received from block nodes");
                    connRecv_counters.put(respType, metrics.getOrCreate(cfg));
                }
            }
        }

        final Counter.Config recvUnknownCfg = newCounter(GROUP_CONN_RECV, "unknown")
                .withDescription("Number of responses received from block nodes that are of unknown types");
        this.connRecv_unknownCounter = metrics.getOrCreate(recvUnknownCfg);

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
    }

    /**
     * Record that an unknown response was received from a block node.
     */
    public void recordUnknownResponseReceived() {
        connRecv_unknownCounter.increment();
    }

    /**
     * Record that a response was received from a block node.
     * @param responseType the type of response received
     */
    public void recordResponseReceived(final PublishStreamResponse.ResponseOneOfType responseType) {
        final Counter counter = connRecv_counters.get(responseType);
        if (counter != null) {
            counter.increment();
        }
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
     * @param responseType the type of end of stream response received
     */
    public void recordResponseEndOfStreamReceived(final PublishStreamResponse.EndOfStream.Code responseType) {
        final Counter counter = connRecv_endOfStreamCounters.get(responseType);
        if (counter != null) {
            counter.increment();
        }
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

    // Connection SEND metrics -----------------------------------------------------------------------------------------

    private void registerConnectionSendMetrics() {
        for (final PublishStreamRequest.RequestOneOfType reqType : PublishStreamRequest.RequestOneOfType.values()) {
            final String reqTypeName = toCamelCase(reqType.protoName());
            switch (reqType) {
                case UNSET -> {
                    /* ignore */
                }
                case END_STREAM -> {
                    for (final PublishStreamRequest.EndStream.Code esCode :
                            PublishStreamRequest.EndStream.Code.values()) {
                        if (PublishStreamRequest.EndStream.Code.UNKNOWN == esCode) {
                            continue;
                        }
                        final String name = reqTypeName + "_" + toCamelCase(esCode.protoName());
                        final Counter.Config cfg = newCounter(GROUP_CONN_SEND, name)
                                .withDescription("Number of " + name + " requests sent to block nodes");
                        connSend_endStreamCounters.put(esCode, metrics.getOrCreate(cfg));
                    }
                }
                default -> {
                    final Counter.Config cfg = newCounter(GROUP_CONN_SEND, reqTypeName)
                            .withDescription("Number of " + reqTypeName + " requests sent to the block nodes");
                    connSend_counters.put(reqType, metrics.getOrCreate(cfg));
                }
            }
        }

        final Counter.Config sendFailureCfg = newCounter(GROUP_CONN_SEND, "failure")
                .withDescription("Number of requests sent to block nodes that failed");
        this.connSend_failureCounter = metrics.getOrCreate(sendFailureCfg);

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

        final RunningAverageMetric.Config publishStreamRequestLatencyCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN_SEND + "_requestSendLatency")
                .withDescription("The average latency (ms) for a PublishStreamRequest to be sent to a block node")
                .withFormat("%,.2f");
        this.connSend_publishStreamRequestLatency = metrics.getOrCreate(publishStreamRequestLatencyCfg);

        final LongGauge.Config latestBlockEndOfBlockCfg = newLongGauge(GROUP_CONN_SEND, "latestBlockEndOfBlock")
                .withDescription("The latest block number for which an EndOfBlock request was sent");
        this.connSend_latestBlockEndOfBlockGauge = metrics.getOrCreate(latestBlockEndOfBlockCfg);

        final LongGauge.Config streamingBlockCfg = newLongGauge(GROUP_CONN_SEND, "streamingBlockNumber")
                .withDescription("The current block number this connection is streaming");
        connSend_streamingBlockNumberGauge = metrics.getOrCreate(streamingBlockCfg);

        final RunningAverageMetric.Config requestBytesCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN_SEND + "_requestBytes")
                .withDescription("The average number of bytes in a PublishStreamRequest")
                .withFormat("%,.2f");
        connSend_requestBytes = metrics.getOrCreate(requestBytesCfg);

        final RunningAverageMetric.Config requestBlockItemCountCfg = new RunningAverageMetric.Config(
                        CATEGORY, GROUP_CONN_SEND + "_requestBlockItemCount")
                .withDescription("The average number of BlockItems in a PublishStreamRequest")
                .withFormat("%,.2f");
        connSend_requestBlockItemCount = metrics.getOrCreate(requestBlockItemCountCfg);
    }

    /**
     * Record that a request was sent to a block node.
     * @param requestType the type of request sent
     */
    public void recordRequestSent(final PublishStreamRequest.RequestOneOfType requestType) {
        final Counter counter = connSend_counters.get(requestType);
        if (counter != null) {
            counter.increment();
        }
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
     * @param requestType the type of end stream request sent
     */
    public void recordRequestEndStreamSent(final PublishStreamRequest.EndStream.Code requestType) {
        final Counter counter = connSend_endStreamCounters.get(requestType);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * Record the latency for a request to be sent to a block node.
     * @param latencyMs the latency in milliseconds
     */
    public void recordRequestLatency(final long latencyMs) {
        connSend_publishStreamRequestLatency.update(latencyMs);
    }

    /**
     * Record that a request to a block node failed to be sent.
     */
    public void recordRequestSendFailure() {
        connSend_failureCounter.increment();
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
     * @param numBytes the size of the request in bytes
     */
    public void recordRequestBytes(final long numBytes) {
        connSend_requestBytes.update(numBytes);
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
