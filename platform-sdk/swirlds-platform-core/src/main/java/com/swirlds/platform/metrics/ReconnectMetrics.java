// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_0;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.common.metrics.extensions.CountPerSecond;
import com.swirlds.common.units.TimeUnit;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.network.PeerInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collection of metrics related to reconnects
 */
public class ReconnectMetrics {

    public static final String RECONNECT_CATEGORY = "Reconnect";

    private static final Counter.Config SENDER_START_TIMES_CONFIG = new Counter.Config(
                    RECONNECT_CATEGORY, "startsReconnectAsSender")
            .withDescription("number of times a node starts reconnect as a sender");
    private final Counter senderStartTimes;

    private static final Counter.Config RECEIVER_START_TIMES_CONFIG = new Counter.Config(
                    RECONNECT_CATEGORY, "startsReconnectAsReceiver")
            .withDescription("number of times a node starts reconnect as a receiver");
    private final Counter receiverStartTimes;

    private static final Counter.Config SENDER_END_TIMES_CONFIG = new Counter.Config(
                    RECONNECT_CATEGORY, "endsReconnectAsSender")
            .withDescription("number of times a node ends reconnect as a sender");
    private final Counter senderEndTimes;

    private static final Counter.Config RECEIVER_END_TIMES_CONFIG = new Counter.Config(
                    RECONNECT_CATEGORY, "endsReconnectAsReceiver")
            .withDescription("number of times a node ends reconnect as a receiver");
    private final Counter receiverEndTimes;
    /**
     * Number of reconnect rejections per second per peer in the address book.
     */
    private final Map<Long, CountPerSecond> rejectionFrequency = new HashMap<>();

    private static final LongAccumulator.Config SENDER_DURATION_CONFIG = new LongAccumulator.Config(
                    RECONNECT_CATEGORY, "senderReconnectDurationSeconds")
            .withInitialValue(0)
            .withAccumulator(Long::sum)
            .withDescription("duration of reconnect as a sender")
            .withUnit(TimeUnit.UNIT_SECONDS.getAbbreviation());
    private final LongAccumulator senderReconnectDurationSeconds;

    private static final LongAccumulator.Config RECEIVER_DURATION_CONFIG = new LongAccumulator.Config(
                    RECONNECT_CATEGORY, "receiverReconnectDurationSeconds")
            .withInitialValue(0)
            .withAccumulator(Long::sum)
            .withDescription("duration of reconnect as a receiver")
            .withUnit(TimeUnit.UNIT_SECONDS.getAbbreviation());
    private final LongAccumulator receiverReconnectDurationSeconds;

    // Assuming that reconnect is a "singleton" operation (a single node cannot teach multiple learners
    // simultaneously, and a single node cannot learn from multiple teachers at once), we maintain
    // state variables here to measure the duration of reconnect operations.
    // A caller of incrementStart/End methods is responsible for synchronizing access to these.
    private long senderStartNanos = 0L;
    private long receiverStartNanos = 0L;

    /**
     * Constructor of {@code ReconnectMetrics}
     *
     * @param metrics
     * 		reference to the metrics-system
     * @param peers the list of peers reflecting the address book to register metrics for
     * @throws IllegalArgumentException if {@code metrics} is {@code null}
     */
    public ReconnectMetrics(@NonNull final Metrics metrics, @NonNull final List<PeerInfo> peers) {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(peers, "peers");
        senderStartTimes = metrics.getOrCreate(SENDER_START_TIMES_CONFIG);
        receiverStartTimes = metrics.getOrCreate(RECEIVER_START_TIMES_CONFIG);
        senderEndTimes = metrics.getOrCreate(SENDER_END_TIMES_CONFIG);
        receiverEndTimes = metrics.getOrCreate(RECEIVER_END_TIMES_CONFIG);
        senderReconnectDurationSeconds = metrics.getOrCreate(SENDER_DURATION_CONFIG);
        receiverReconnectDurationSeconds = metrics.getOrCreate(RECEIVER_DURATION_CONFIG);

        peers.forEach(entry -> {
            final long nodeId = entry.nodeId().id();
            rejectionFrequency.put(
                    nodeId,
                    new CountPerSecond(
                            metrics,
                            new CountPerSecond.Config(
                                            PLATFORM_CATEGORY,
                                            String.format("reconnectRejections_per_sec_%02d", nodeId))
                                    .withDescription(String.format(
                                            "number of reconnections rejected per second from node %02d", nodeId))
                                    .withUnit("rejectionsPerSec")
                                    .withFormat(FORMAT_10_0)));
        });
    }

    public void incrementSenderStartTimes() {
        senderStartNanos = System.nanoTime();
        senderStartTimes.increment();
    }

    public void incrementReceiverStartTimes() {
        receiverStartNanos = System.nanoTime();
        receiverStartTimes.increment();
    }

    public void incrementSenderEndTimes() {
        senderEndTimes.increment();
        senderReconnectDurationSeconds.update(
                Duration.ofNanos(System.nanoTime() - senderStartNanos).toSeconds());
    }

    public void incrementReceiverEndTimes() {
        receiverEndTimes.increment();
        receiverReconnectDurationSeconds.update(
                Duration.ofNanos(System.nanoTime() - receiverStartNanos).toSeconds());
    }

    /**
     * Records the occurrence of rejecting a reconnect attempt from a peer.
     *
     * @param nodeId the peer being rejected.
     */
    public void recordReconnectRejection(final long nodeId) {
        final Long id = nodeId;
        if (rejectionFrequency.containsKey(id)) {
            rejectionFrequency.get(id).count();
        }
    }
}
