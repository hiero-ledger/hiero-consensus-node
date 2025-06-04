// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.metrics;

import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.PublishStreamResponse.EndOfStream.Code;

/**
 * Metrics related to the block stream service, specifically tracking responses received
 * from block nodes during publishing for the local node.
 */
@Singleton
public class BlockStreamMetrics {
    private static final Logger logger = LogManager.getLogger(BlockStreamMetrics.class);
    private static final String APP_CATEGORY = "app";

    private final Metrics metrics;
    private final NodeInfo selfNodeInfo;

    // Map: EndOfStream Code -> Counter
    private final Map<EndOfStream.Code, Counter> endOfStreamCounters = new EnumMap<>(EndOfStream.Code.class);
    // Counter for SkipBlock responses
    private Counter skipBlockCounter;
    // Counter for ResendBlock responses
    private Counter resendBlockCounter;
    // Counter for Acknowledgement responses
    private Counter acknowledgedBlockCounter;
    // Counter for BlockNodeConnection.onError invocations
    private Counter blockNodeConnectionErrorCounter;
    private Counter unknownRespCounter;
    private LongGauge producingBlockNumberGauge;
    private LongGauge oldestUnacknowledgedBlockTimeGauge;
    private LongGauge latestAcknowledgedBlockNumberGauge;
    private DoubleGauge blockBufferSaturationGauge;

    @Inject
    public BlockStreamMetrics(@NonNull final Metrics metrics, @NonNull final NodeInfo selfNodeInfo) {
        this.metrics = requireNonNull(metrics);
        this.selfNodeInfo = requireNonNull(selfNodeInfo);
    }

    /**
     * Registers the metrics for this node, including the node ID in the metric names.
     * This should be called once during initialization after NetworkInfo is available.
     */
    public void registerMetrics() {
        final long localNodeId = selfNodeInfo.nodeId();
        final String nodeLabel = "_node" + localNodeId;
        logger.info("Registering BlockStreamMetrics for node {}", localNodeId);

        // Register EndOfStream counters for each possible code
        for (final EndOfStream.Code code : EndOfStream.Code.values()) {
            // Skip UNKNOWN/UNSET value if necessary, though counting it might be useful
            if (code == Code.UNKNOWN) continue;

            final String metricName = "endOfStream_" + code.name() + nodeLabel;
            final Counter counter = metrics.getOrCreate(new Counter.Config(APP_CATEGORY, metricName)
                    .withDescription("Total number of EndOfStream responses with code " + code.name()
                            + " received by node " + localNodeId));
            endOfStreamCounters.put(code, counter);
        }

        // Register SkipBlock counter
        final String skipMetricName = "skipBlock" + nodeLabel;
        skipBlockCounter = metrics.getOrCreate(new Counter.Config(APP_CATEGORY, skipMetricName)
                .withDescription("Total number of SkipBlock responses received by node " + localNodeId));

        // Register ResendBlock counter
        final String resendMetricName = "resendBlock" + nodeLabel;
        resendBlockCounter = metrics.getOrCreate(new Counter.Config(APP_CATEGORY, resendMetricName)
                .withDescription("Total number of ResendBlock responses received by node " + localNodeId));

        // Register Block Acknowledgement counter
        final String ackMetricName = "acknowledgeBlock" + nodeLabel;
        acknowledgedBlockCounter = metrics.getOrCreate(new Counter.Config(APP_CATEGORY, ackMetricName)
                .withDescription("Total number of block acknowledgements received by node " + localNodeId));

        // Register blockNodeConnectionError counter
        final String blockNodeConnectionErrorMetricName = "blockNodeConnectionErrorCount" + nodeLabel;
        blockNodeConnectionErrorCounter =
                metrics.getOrCreate(new Counter.Config(APP_CATEGORY, blockNodeConnectionErrorMetricName)
                        .withDescription("Total number of block node connection errors for node " + localNodeId));

        // Register Producing Block Number gauge
        final String producingBlockNumMetricName = "producingBlockNumber" + nodeLabel;
        producingBlockNumberGauge = metrics.getOrCreate(new LongGauge.Config(APP_CATEGORY, producingBlockNumMetricName)
                .withDescription("Current block number being produced by node " + localNodeId));

        // Register Oldest Unacknowledged Block Time gauge
        final String oldestUnackTimeMetricName = "timeOfOldestUnacknowledgedBlock" + nodeLabel;
        oldestUnacknowledgedBlockTimeGauge =
                metrics.getOrCreate(new LongGauge.Config(APP_CATEGORY, oldestUnackTimeMetricName)
                        .withDescription("Timestamp of the oldest unacknowledged block for node " + localNodeId));

        // Register Latest Acknowledged Block Number gauge
        final String latestAckBlockNumMetricName = "latestAcknowledgedBlockNumber" + nodeLabel;
        latestAcknowledgedBlockNumberGauge =
                metrics.getOrCreate(new LongGauge.Config(APP_CATEGORY, latestAckBlockNumMetricName)
                        .withDescription("Latest block number acknowledged for node " + localNodeId));

        final String unknownRespMetricName = "unknownBlockNodeResponse" + nodeLabel;
        unknownRespCounter = metrics.getOrCreate(new Counter.Config(APP_CATEGORY, unknownRespMetricName)
                .withDescription("Total number of unexpected responses received from block nodes"));

        /*
        Buffer saturation gauge - higher values mean the buffer is nearing saturation. Values over 100 mean the buffer
        is saturated and "overflowing" though this should be minimal since backpressure should be applied to prevent
        more blocks from being created
         */
        final String bufferSaturationMetricName = "blockBufferSaturation" + nodeLabel;
        blockBufferSaturationGauge =
                metrics.getOrCreate(new DoubleGauge.Config(APP_CATEGORY, bufferSaturationMetricName)
                        .withDescription("Block buffer saturation; Values closer to 100 mean the buffer is nearing"
                                + "saturation and backpressure may be applied, and values at or above 100 mean the "
                                + "buffer is fully saturated and potentially overflowing"));

        logger.info("Finished registering BlockStreamMetrics for node {}", localNodeId);
    }

    /**
     * Increments the counter for a specific EndOfStream response code received.
     *
     * @param code   The {@link EndOfStream.Code} received.
     */
    public void incrementEndOfStreamCount(@NonNull final EndOfStream.Code code) {
        if (code != Code.UNKNOWN) {
            final var counter = endOfStreamCounters.get(code);
            if (counter != null) {
                counter.increment();
            } else {
                // Should not happen if registration was successful for all codes
                logger.warn("EndOfStream counter for code {} not found.", code);
            }
        }
    }

    /**
     * Increments the counter for SkipBlock responses received.
     */
    public void incrementSkipBlockCount() {
        if (skipBlockCounter != null) {
            skipBlockCounter.increment();
        } else {
            // Should not happen if registration was successful
            logger.warn("SkipBlock counter not found.");
        }
    }

    /**
     * Increments the counter for ResendBlock responses received.
     */
    public void incrementResendBlockCount() {
        if (resendBlockCounter != null) {
            resendBlockCounter.increment();
        } else {
            // Should not happen if registration was successful
            logger.warn("ResendBlock counter not found.");
        }
    }

    /**
     * Increments the counter for Block Acknowledgement responses received.
     */
    public void incrementAcknowledgedBlockCount() {
        if (acknowledgedBlockCounter != null) {
            acknowledgedBlockCounter.increment();
        } else {
            // Should not happen if registration was successful
            logger.warn("acknowledgedBlockCounter not found.");
        }
    }

    public void incrementUnknownResponseCount() {
        if (unknownRespCounter != null) {
            unknownRespCounter.increment();
        } else {
            logger.warn("unknownRespCounter not found");
        }
    }

    /**
     * Sets the current block number being produced.
     *
     * @param blockNumber The current block number.
     */
    public void setProducingBlockNumber(final long blockNumber) {
        if (producingBlockNumberGauge != null) {
            producingBlockNumberGauge.set(blockNumber);
        } else {
            // Should not happen if registration was successful
            logger.warn("producingBlockNumberGauge not found.");
        }
    }

    /**
     * Sets the timestamp of the oldest unacknowledged block.
     *
     * @param timestamp The timestamp in milliseconds since epoch.
     */
    public void setOldestUnacknowledgedBlockTime(final long timestamp) {
        if (oldestUnacknowledgedBlockTimeGauge != null) {
            oldestUnacknowledgedBlockTimeGauge.set(timestamp);
        } else {
            // Should not happen if registration was successful
            logger.warn("oldestUnacknowledgedBlockTimeGauge not found.");
        }
    }

    /**
     * Sets the latest block number that has been acknowledged.
     *
     * @param blockNumber The block number of the latest acknowledgement.
     */
    public void setLatestAcknowledgedBlockNumber(final long blockNumber) {
        if (latestAcknowledgedBlockNumberGauge != null) {
            latestAcknowledgedBlockNumberGauge.set(blockNumber);
        } else {
            // Should not happen if registration was successful
            logger.warn("latestAcknowledgedBlockNumberGauge not found.");
        }
    }

    /**
     * Increments the counter for onError invocations.
     */
    public void incrementOnErrorCount() {
        if (blockNodeConnectionErrorCounter != null) {
            blockNodeConnectionErrorCounter.increment();
        } else {
            // Should not happen if registration was successful
            logger.warn("onErrorCounter not found.");
        }
    }

    /**
     * Updates the current block buffer saturation percent.
     * @param saturation the current block buffer saturation percent
     */
    public void updateBlockBufferSaturation(final double saturation) {
        if (blockBufferSaturationGauge != null) {
            blockBufferSaturationGauge.set(saturation);
        }
    }
}
