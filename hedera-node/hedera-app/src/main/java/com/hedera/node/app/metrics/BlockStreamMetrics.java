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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamResponse;

/**
 * Metrics related to the block stream service, specifically tracking responses received
 * from block nodes during publishing for the local node.
 */
@Singleton
public class BlockStreamMetrics {
    private static final Logger logger = LogManager.getLogger(BlockStreamMetrics.class);

    private static final String CATEGORY = "blockStream";

    private static final String GROUP_CONN = "conn";
    private static final String GROUP_CONN_SEND = "connSend";
    private static final String GROUP_CONN_RECV = "connRecv";
    private static final String GROUP_BUFFER = "buffer";

    private final Metrics metrics;

    // connection send metrics
    private Counter connSend_failureCounter;
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

    // connectivity metrics
    private Counter conn_onCompleteCounter;
    private Counter conn_onErrorCounter;
    private Counter conn_openedCounter;
    private Counter conn_closedCounter;
    private Counter conn_noActiveCounter;
    private Counter conn_createFailureCounter;

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

    @Inject
    public BlockStreamMetrics(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);

        registerConnectionSendMetrics();
        registerConnectionRecvMetrics();
        registerConnectivityMetrics();
        registerBufferMetrics();
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
    }

    public void recordBufferSaturation(final double saturation) {
        buffer_saturationGauge.set(saturation);
    }

    public void recordLatestBlockOpened(final long blockNumber) {
        buffer_latestBlockOpenedGauge.set(blockNumber);
    }

    public void recordLatestBlockAcked(final long blockNumber) {
        buffer_latestBlockAckedGauge.set(blockNumber);
    }

    public void recordNumberOfBlocksPruned(final int numBlocksPruned) {
        buffer_numBlocksPrunedCounter.add(numBlocksPruned);
    }

    public void recordBlockOpened() {
        buffer_numBlocksOpenedCounter.increment();
    }

    public void recordBlockClosed() {
        buffer_numBlocksClosedCounter.increment();
    }

    public void recordBlockMissing() {
        buffer_numBlocksMissingCounter.increment();
    }

    public void recordBackPressureActive() {
        buffer_backPressureStateGauge.set(BACK_PRESSURE_ACTIVE);
    }

    public void recordBackPressureActionStage() {
        buffer_backPressureStateGauge.set(BACK_PRESSURE_ACTION_STAGE);
    }

    public void recordBackPressureRecovering() {
        buffer_backPressureStateGauge.set(BACK_PRESSURE_RECOVERING);
    }

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

        final Counter.Config noActiveCfg = newCounter(GROUP_CONN, "noActive")
                .withDescription("Number of times streaming a block was attempted but there was no active connection");
        conn_noActiveCounter = metrics.getOrCreate(noActiveCfg);

        final Counter.Config createFailureCfg = newCounter(GROUP_CONN, "createFailure")
                .withDescription("Number of times establishing a block node connection failed");
        conn_createFailureCounter = metrics.getOrCreate(createFailureCfg);
    }

    public void recordConnectionOnComplete() {
        conn_onCompleteCounter.increment();
    }

    public void recordConnectionOnError() {
        conn_onErrorCounter.increment();
    }

    public void recordConnectionOpened() {
        conn_openedCounter.increment();
    }

    public void recordConnectionClosed() {
        conn_closedCounter.increment();
    }

    public void recordNoActiveConnection() {
        conn_noActiveCounter.increment();
    }

    public void recordConnectionCreateFailure() {
        conn_createFailureCounter.increment();
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
                        if (PublishStreamResponse.EndOfStream.Code.UNKNOWN == eosCode) {
                            continue;
                        }
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
    }

    public void recordUnknownResponseReceived() {
        connRecv_unknownCounter.increment();
    }

    public void recordResponseReceived(final PublishStreamResponse.ResponseOneOfType responseType) {
        final Counter counter = connRecv_counters.get(responseType);
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordResponseEndOfStreamReceived(final PublishStreamResponse.EndOfStream.Code responseType) {
        final Counter counter = connRecv_endOfStreamCounters.get(responseType);
        if (counter != null) {
            counter.increment();
        }
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
    }

    public void recordRequestSent(final PublishStreamRequest.RequestOneOfType requestType) {
        final Counter counter = connSend_counters.get(requestType);
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordRequestEndStreamSent(final PublishStreamRequest.EndStream.Code requestType) {
        final Counter counter = connSend_endStreamCounters.get(requestType);
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordRequestSendFailure() {
        connSend_failureCounter.increment();
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
