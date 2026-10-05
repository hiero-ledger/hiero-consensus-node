// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.benchmark.tools.histogram.LatencyHistogram;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConsensusLatencyRecorder}.
 */
@DisplayName("ConsensusLatencyRecorder Test")
class ConsensusLatencyRecorderTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final NodeId NODE_A = NodeId.of(0);
    private static final NodeId NODE_B = NodeId.of(1);

    /**
     * The histograms report a percentile as the upper end of its bucket, which is at most 2^-10 of the value above the
     * exact value. This is the tolerance used when comparing a percentile with the exact one.
     */
    private static final double PERCENTILE_TOLERANCE = 1.001;

    private ConsensusLatencyRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ConsensusLatencyRecorder();
        recorder.registerNode(NODE_A);
        recorder.registerNode(NODE_B);
        recorder.markStarted(START);
    }

    @Test
    @DisplayName("The C2C of every event is recorded for every node")
    void recordsEveryEventOfEveryNode() {
        // Round 1: events created at +0ms and +10ms, reached at +100ms on A and +120ms on B
        final List<PlatformEvent> round1Events = List.of(eventCreatedAt(0), eventCreatedAt(10));
        recorder.onConsensusRound(NODE_A, round(100, round1Events));
        recorder.onConsensusRound(NODE_B, round(120, round1Events));

        // Round 2: event created at +50ms, reached at +200ms on A and +150ms on B
        final List<PlatformEvent> round2Events = List.of(eventCreatedAt(50));
        recorder.onConsensusRound(NODE_A, round(200, round2Events));
        recorder.onConsensusRound(NODE_B, round(150, round2Events));

        final ConsensusLatencyResult result = recorder.snapshot(START.plusSeconds(1));

        // A observed 100, 90 and 150ms (sum 340ms); B observed 120, 110 and 100ms (sum 330ms)
        assertThat(result.nodes()).isEqualTo(2);
        assertThat(result.c2cSamples()).isEqualTo(6);
        assertThat(result.minC2C()).isEqualTo(Duration.ofMillis(90));
        assertThat(result.meanC2C()).isEqualTo(Duration.ofNanos(670_000_000L / 6));
        assertThat(result.maxC2C()).isEqualTo(Duration.ofMillis(150));

        assertThat(result.c2cPerNode().keySet()).containsExactly(NODE_A, NODE_B);
        final LatencyHistogram a = result.c2cPerNode().get(NODE_A);
        assertThat(a.totalCount()).isEqualTo(3);
        assertThat(a.min()).isEqualTo(Duration.ofMillis(90).toNanos());
        assertThat(a.mean()).isEqualTo(340_000_000.0 / 3);
        assertThat(a.max()).isEqualTo(Duration.ofMillis(150).toNanos());
        final LatencyHistogram b = result.c2cPerNode().get(NODE_B);
        assertThat(b.totalCount()).isEqualTo(3);
        assertThat(b.min()).isEqualTo(Duration.ofMillis(100).toNanos());
        assertThat(b.mean()).isEqualTo(Duration.ofMillis(110).toNanos());
        assertThat(b.max()).isEqualTo(Duration.ofMillis(120).toNanos());
    }

    @Test
    @DisplayName("Each node is measured with its own reached timestamp")
    void usesReachedTimestampOfEachNode() {
        final List<PlatformEvent> events = List.of(eventCreatedAt(0));
        recorder.onConsensusRound(NODE_A, round(100, events));
        recorder.onConsensusRound(NODE_B, round(300, events));

        final ConsensusLatencyResult result = recorder.snapshot(START.plusSeconds(1));

        assertThat(result.c2cPerNode().get(NODE_A).max())
                .isEqualTo(Duration.ofMillis(100).toNanos());
        assertThat(result.c2cPerNode().get(NODE_B).max())
                .isEqualTo(Duration.ofMillis(300).toNanos());
        assertThat(result.meanC2C()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("Percentiles describe the distribution of the C2C, not only its average and maximum")
    void reportsPercentiles() {
        // 100 events with a C2C of 1ms, 2ms, ..., 100ms, all reaching consensus at +200ms
        final List<PlatformEvent> events = new ArrayList<>();
        for (int c2cMillis = 1; c2cMillis <= 100; c2cMillis++) {
            events.add(eventCreatedAt(200 - c2cMillis));
        }
        recorder.onConsensusRound(NODE_A, round(200, events));

        final ConsensusLatencyResult result = recorder.snapshot(START.plusSeconds(1));

        assertThat(result.c2cSamples()).isEqualTo(100);
        assertWithinPercentileTolerance(result.c2cPercentile(50), Duration.ofMillis(50));
        assertWithinPercentileTolerance(result.c2cPercentile(90), Duration.ofMillis(90));
        assertWithinPercentileTolerance(result.c2cPercentile(99), Duration.ofMillis(99));
        assertThat(result.c2cPercentile(100)).isEqualTo(Duration.ofMillis(100));
        assertThat(result.c2cPercentile(0)).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    @DisplayName("Rounds without events do not contribute, and an empty result reports zero latencies")
    void ignoresEmptyRounds() {
        recorder.onConsensusRound(NODE_A, round(100, List.of()));
        recorder.onConsensusRound(NODE_B, round(100, List.of()));

        final ConsensusLatencyResult result = recorder.snapshot(START.plusSeconds(1));

        assertThat(result.c2cSamples()).isZero();
        assertThat(result.minC2C()).isEqualTo(Duration.ZERO);
        assertThat(result.meanC2C()).isEqualTo(Duration.ZERO);
        assertThat(result.c2cPercentile(99)).isEqualTo(Duration.ZERO);
        assertThat(result.maxC2C()).isEqualTo(Duration.ZERO);
        assertThat(result.toString()).isNotBlank();
    }

    @Test
    @DisplayName("A snapshot is not changed by rounds recorded after it was taken")
    void snapshotIsIndependentOfLaterRecording() {
        recorder.onConsensusRound(NODE_A, round(100, List.of(eventCreatedAt(0))));
        final ConsensusLatencyResult before = recorder.snapshot(START.plusSeconds(1));

        recorder.onConsensusRound(NODE_A, round(500, List.of(eventCreatedAt(0))));
        final ConsensusLatencyResult after = recorder.snapshot(START.plusSeconds(1));

        assertThat(before.c2cSamples()).isEqualTo(1);
        assertThat(before.maxC2C()).isEqualTo(Duration.ofMillis(100));
        assertThat(before.c2cPerNode().get(NODE_A).totalCount()).isEqualTo(1);
        assertThat(after.c2cSamples()).isEqualTo(2);
        assertThat(after.maxC2C()).isEqualTo(Duration.ofMillis(500));
        assertThat(after.c2cPerNode().get(NODE_A).totalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Event and byte throughput are measured per second of simulated time")
    void computesThroughput() {
        final GossipEvent gossipEvent =
                GossipEvent.newBuilder().signature(Bytes.wrap(new byte[64])).build();
        final long eventSize = GossipEvent.PROTOBUF.measureRecord(gossipEvent);
        for (int i = 0; i < 4; i++) {
            final PlatformEvent event = mock(PlatformEvent.class);
            when(event.getGossipEvent()).thenReturn(gossipEvent);
            recorder.onEventCreated(event);
        }

        final ConsensusLatencyResult result = recorder.snapshot(START.plusSeconds(2));

        assertThat(result.eventsPerSec()).isEqualTo(2);
        assertThat(result.bytesPerSec()).isEqualTo(2 * eventSize);
    }

    @Test
    @DisplayName("Only the first call of markStarted defines the start")
    void onlyFirstStartCounts() {
        recorder.markStarted(START.plusSeconds(5));
        recorder.onConsensusRound(NODE_A, round(100, List.of(eventCreatedAt(0))));

        assertThat(recorder.snapshot(START.plusSeconds(1)).maxC2C()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("A snapshot cannot be taken before the network was started")
    void snapshotRequiresStart() {
        final ConsensusLatencyRecorder notStarted = new ConsensusLatencyRecorder();
        notStarted.registerNode(NODE_A);

        assertThatThrownBy(() -> notStarted.snapshot(START)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Rounds of unregistered nodes are rejected")
    void rejectsUnregisteredNodes() {
        final ConsensusRound round = round(100, List.of(eventCreatedAt(0)));

        assertThatThrownBy(() -> recorder.onConsensusRound(NodeId.of(42), round))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("An event that reached consensus before it was created is rejected")
    void rejectsNegativeLatency() {
        final ConsensusRound round = round(100, List.of(eventCreatedAt(150)));

        assertThatThrownBy(() -> recorder.onConsensusRound(NODE_A, round)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Asserts that a percentile of the histogram is not below the exact value, and at most one bucket width above it.
     *
     * @param actual the percentile reported by the result
     * @param exact the exact percentile
     */
    private static void assertWithinPercentileTolerance(final Duration actual, final Duration exact) {
        assertThat(actual.toNanos()).isBetween(exact.toNanos(), (long) (exact.toNanos() * PERCENTILE_TOLERANCE));
    }

    /**
     * Creates a mocked event with the given creation time.
     *
     * @param createdMillis the creation time in milliseconds after {@link #START}
     * @return the event
     */
    private static PlatformEvent eventCreatedAt(final long createdMillis) {
        final PlatformEvent event = mock(PlatformEvent.class);
        when(event.getTimeCreated()).thenReturn(START.plusMillis(createdMillis));
        return event;
    }

    /**
     * Creates a mocked consensus round.
     *
     * @param reachedMillis the time the round reached consensus, in milliseconds after {@link #START}
     * @param events the events of the round
     * @return the round
     */
    private static ConsensusRound round(final long reachedMillis, final List<PlatformEvent> events) {
        final ConsensusRound round = mock(ConsensusRound.class);
        when(round.getReachedConsTimestamp()).thenReturn(START.plusMillis(reachedMillis));
        when(round.getConsensusEvents()).thenReturn(events);
        return round;
    }
}
