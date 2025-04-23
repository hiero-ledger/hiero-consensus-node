// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.metrics;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.PublishStreamResponseCode;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Metrics related to the block stream service, specifically tracking responses received
 * from block nodes during publishing for the local node.
 */
@Singleton
public class BlockStreamMetrics {
    private static final Logger logger = LogManager.getLogger(BlockStreamMetrics.class);
    private static final String APP_CATEGORY = "app";

    private final Metrics metrics;
    private final NetworkInfo networkInfo;

    // Map: EndOfStream Code -> Counter
    private final Map<PublishStreamResponseCode, Counter> endOfStreamCounters =
            new EnumMap<>(PublishStreamResponseCode.class);
    // Counter for SkipBlock responses
    private Counter skipBlockCounter;
    // Counter for ResendBlock responses
    private Counter resendBlockCounter;
    // Counter for Acknowledgement responses
    private Counter blockAckReceivedCounter;

    private LongGauge producingBlockNumberGauge;

    @Inject
    public BlockStreamMetrics(@NonNull final Metrics metrics, @NonNull final NetworkInfo networkInfo) {
        this.metrics = requireNonNull(metrics);
        this.networkInfo = requireNonNull(networkInfo);
        registerMetrics();
    }

    /**
     * Registers the metrics for this node, including the node ID in the metric names.
     * This should be called once during initialization after NetworkInfo is available.
     */
    public void registerMetrics() {
        final long localNodeId = networkInfo.selfNodeInfo().nodeId();
        final String nodeLabel = "_node" + localNodeId;
        logger.info("Registering BlockStreamMetrics for node {}", localNodeId);

        // Register EndOfStream counters for each possible code
        for (final PublishStreamResponseCode code : PublishStreamResponseCode.values()) {
            // Skip UNKNOWN/UNSET value if necessary, though counting it might be useful
            if (code == PublishStreamResponseCode.STREAM_ITEMS_UNKNOWN) continue;

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
        final String ackMetricName = "blockAckReceivedCount" + nodeLabel;
        blockAckReceivedCounter = metrics.getOrCreate(new Counter.Config(APP_CATEGORY, ackMetricName)
                .withDescription("Total number of block acknowledgements received by node " + localNodeId));

        // Register Producing Block Number gauge
        final String producingBlockNumMetricName = "producingBlockNumber" + nodeLabel;
        producingBlockNumberGauge = metrics.getOrCreate(new LongGauge.Config(APP_CATEGORY, producingBlockNumMetricName)
                .withDescription("Current block number being produced by node " + localNodeId));

        logger.info("Finished registering BlockStreamMetrics for node {}", localNodeId);
    }

    /**
     * Increments the counter for a specific EndOfStream response code received.
     *
     * @param code   The {@link PublishStreamResponseCode} received.
     */
    public void incrementEndOfStreamCount(@NonNull final PublishStreamResponseCode code) {
        if (code != PublishStreamResponseCode.STREAM_ITEMS_UNKNOWN) {
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
    public void incrementBlockAckReceivedCount() {
        if (blockAckReceivedCounter != null) {
            blockAckReceivedCounter.increment();
        } else {
            // Should not happen if registration was successful
            logger.warn("blockAckReceivedCounter not found.");
        }
    }

    /**
     * Sets the current block number being produced.
     *
     * @param blockNumber The current block number.
     */
    public void setProducingBlockNumber(long blockNumber) {
        if (producingBlockNumberGauge != null) {
            producingBlockNumberGauge.set(blockNumber);
        } else {
            // Should not happen if registration was successful
            logger.warn("producingBlockNumberGauge not found.");
        }
    }
}
