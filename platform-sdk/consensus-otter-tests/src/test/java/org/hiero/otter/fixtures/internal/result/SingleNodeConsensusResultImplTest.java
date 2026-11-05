// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.junit.jupiter.api.Test;

/**
 * Tests how {@link SingleNodeConsensusResultImpl} returns the pre-consensus and stale events of a
 * {@link NodeResultsCollector} and the ratios built on them.
 */
class SingleNodeConsensusResultImplTest {

    private static final NodeId NODE_ID = NodeId.of(0L);

    private int nextHash = 0;

    @Test
    void clearDiscardsEarlierEventsOfThisResultOnly() {
        final NodeResultsCollector collector = new NodeResultsCollector(NODE_ID, new ConsensusRoundPool(), true);
        final EventDescriptorWrapper earlyPreConsensusEvent = event();
        final EventDescriptorWrapper earlyStaleEvent = event();
        final EventDescriptorWrapper latePreConsensusEvent = event();
        final EventDescriptorWrapper lateStaleEvent = event();

        final SingleNodeConsensusResult clearedResult = collector.newConsensusResult();
        collector.addPreConsensusEvent(earlyPreConsensusEvent);
        collector.addStaleEvent(earlyStaleEvent);
        clearedResult.clear();
        collector.addPreConsensusEvent(latePreConsensusEvent);
        collector.addStaleEvent(lateStaleEvent);
        final SingleNodeConsensusResult newResult = collector.newConsensusResult();

        assertThat(clearedResult.isCleared()).isTrue();
        assertThat(clearedResult.preConsensusEvents()).containsExactly(latePreConsensusEvent);
        assertThat(clearedResult.staleEvents()).containsExactly(lateStaleEvent);

        assertThat(newResult.isCleared()).isFalse();
        assertThat(newResult.preConsensusEvents()).containsExactly(earlyPreConsensusEvent, latePreConsensusEvent);
        assertThat(newResult.staleEvents()).containsExactly(earlyStaleEvent, lateStaleEvent);
    }

    @Test
    void ratiosCountFromTheLastClear() {
        final NodeResultsCollector collector = new NodeResultsCollector(NODE_ID, new ConsensusRoundPool(), true);
        final SingleNodeConsensusResult clearedResult = collector.newConsensusResult();

        // Before clear(): three pre-consensus events, one of which reaches consensus
        collector.addPreConsensusEvent(event());
        collector.addPreConsensusEvent(event());
        collector.addPreConsensusEvent(event());
        collector.addConsensusRound(round(1L, 1));
        clearedResult.clear();

        // After clear(): two pre-consensus events, then a round with three consensus events and one stale event. Some
        // of them were emitted before clear(), so the consensus ratio of the cleared result exceeds 1.0.
        collector.addPreConsensusEvent(event());
        collector.addPreConsensusEvent(event());
        collector.addConsensusRound(round(2L, 3));
        collector.addStaleEvent(event());
        final SingleNodeConsensusResult newResult = collector.newConsensusResult();

        assertThat(clearedResult.consensusRatio()).isEqualTo(1.5);
        assertThat(clearedResult.staleRatio()).isEqualTo(0.5);

        assertThat(newResult.consensusRatio()).isEqualTo(0.8);
        assertThat(newResult.staleRatio()).isEqualTo(0.2);
    }

    @Test
    void ratiosAreNaNWithoutPreConsensusEvents() {
        final NodeResultsCollector collector = new NodeResultsCollector(NODE_ID, new ConsensusRoundPool(), true);
        collector.addConsensusRound(round(1L, 1));
        collector.addStaleEvent(event());
        final SingleNodeConsensusResult result = collector.newConsensusResult();

        assertThat(result.consensusRatio()).isNaN();
        assertThat(result.staleRatio()).isNaN();
    }

    @Test
    void eventsAreUnsupportedIfTheCollectorDoesNotCollectThem() {
        final NodeResultsCollector collector = new NodeResultsCollector(NODE_ID, new ConsensusRoundPool(), false);
        final SingleNodeConsensusResult result = collector.newConsensusResult();

        result.clear();

        assertThat(result.isCleared()).isTrue();
        assertThatThrownBy(result::preConsensusEvents).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(result::staleEvents).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(result::consensusRatio).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(result::staleRatio).isInstanceOf(UnsupportedOperationException.class);
    }

    @NonNull
    private static ConsensusRound round(final long roundNum, final int consensusEventCount) {
        final List<PlatformEvent> consensusEvents = Stream.generate(() -> mock(PlatformEvent.class))
                .limit(consensusEventCount)
                .toList();
        final ConsensusRound round = mock(ConsensusRound.class);
        when(round.getRoundNum()).thenReturn(roundNum);
        when(round.getConsensusEvents()).thenReturn(consensusEvents);
        return round;
    }

    @NonNull
    private EventDescriptorWrapper event() {
        final byte[] hash = ByteBuffer.allocate(DigestType.SHA_384.digestLength())
                .putInt(nextHash++)
                .array();
        return new EventDescriptorWrapper(new Hash(hash, DigestType.SHA_384), NODE_ID, 1L);
    }
}
