// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    private ConsensusLatencyRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ConsensusLatencyRecorder();
        recorder.registerNode(NODE_A);
        recorder.registerNode(NODE_B);
        recorder.markStarted(START);
    }

    @Test
    @DisplayName("Average and maximum C2C are computed over all nodes and per node")
    void computesAverageAndMaximum() {
        // Round 1: events created at +0ms and +10ms, reached at +100ms on A and +120ms on B
        final List<PlatformEvent> round1Events = List.of(eventCreatedAt(0), eventCreatedAt(10));
        recorder.onConsensusRound(NODE_A, round(1, 100, round1Events));
        recorder.onConsensusRound(NODE_B, round(1, 120, round1Events));

        // Round 2: event created at +50ms, reached at +200ms on A and +150ms on B
        final List<PlatformEvent> round2Events = List.of(eventCreatedAt(50));
        recorder.onConsensusRound(NODE_A, round(2, 200, round2Events));
        recorder.onConsensusRound(NODE_B, round(2, 150, round2Events));

        final ConsensusLatencyResult result = recorder.snapshot(START.plusSeconds(1));

        // A observed 100, 90 and 150ms (sum 340ms); B observed 120, 110 and 100ms (sum 330ms)
        assertThat(result.nodes()).isEqualTo(2);
        assertThat(result.averageC2C()).isEqualTo(Duration.ofNanos(670_000_000L / 6));
        assertThat(result.maxC2C()).isEqualTo(Duration.ofMillis(150));
        assertThat(result.averageC2CPerNode())
                .containsExactly(
                        entry(NODE_A, Duration.ofNanos(340_000_000L / 3)), entry(NODE_B, Duration.ofMillis(110)));
    }

    @Test
    @DisplayName("Each node is measured with its own reached timestamp")
    void usesReachedTimestampOfEachNode() {
        final List<PlatformEvent> events = List.of(eventCreatedAt(0));
        recorder.onConsensusRound(NODE_A, round(1, 100, events));
        recorder.onConsensusRound(NODE_B, round(1, 300, events));

        final ConsensusLatencyResult result = recorder.snapshot(START.plusSeconds(1));

        assertThat(result.averageC2CPerNode().get(NODE_A)).isEqualTo(Duration.ofMillis(100));
        assertThat(result.averageC2CPerNode().get(NODE_B)).isEqualTo(Duration.ofMillis(300));
        assertThat(result.averageC2C()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("Cached round aggregates are evicted once every node has reported the round")
    void evictsRoundsReportedByAllNodes() {
        final List<PlatformEvent> events = List.of(eventCreatedAt(0));

        recorder.onConsensusRound(NODE_A, round(1, 100, events));
        assertThat(recorder.cachedRounds()).isEqualTo(1);

        recorder.onConsensusRound(NODE_B, round(1, 100, events));
        assertThat(recorder.cachedRounds()).isZero();
    }

    @Test
    @DisplayName("Empty rounds do not contribute to the C2C")
    void ignoresEmptyRounds() {
        recorder.onConsensusRound(NODE_A, round(1, 100, List.of()));
        recorder.onConsensusRound(NODE_B, round(1, 100, List.of()));

        final ConsensusLatencyResult result = recorder.snapshot(START.plusSeconds(1));

        assertThat(result.averageC2C()).isEqualTo(Duration.ZERO);
        assertThat(result.maxC2C()).isEqualTo(Duration.ZERO);
        assertThat(recorder.cachedRounds()).isZero();
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
        recorder.onConsensusRound(NODE_A, round(1, 100, List.of(eventCreatedAt(0))));

        assertThat(recorder.snapshot(START.plusSeconds(1)).averageC2CPerNode().get(NODE_A))
                .isEqualTo(Duration.ofMillis(100));
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
        final ConsensusRound round = round(1, 100, List.of(eventCreatedAt(0)));

        assertThatThrownBy(() -> recorder.onConsensusRound(NodeId.of(42), round))
                .isInstanceOf(IllegalArgumentException.class);
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
     * @param roundNum the round number
     * @param reachedMillis the time the round reached consensus, in milliseconds after {@link #START}
     * @param events the events of the round
     * @return the round
     */
    private static ConsensusRound round(
            final long roundNum, final long reachedMillis, final List<PlatformEvent> events) {
        final ConsensusRound round = mock(ConsensusRound.class);
        when(round.getRoundNum()).thenReturn(roundNum);
        when(round.getReachedConsTimestamp()).thenReturn(START.plusMillis(reachedMillis));
        when(round.getConsensusEvents()).thenReturn(events);
        when(round.getNumEvents()).thenReturn(events.size());
        return round;
    }
}
