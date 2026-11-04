// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.junit.jupiter.api.Test;

/**
 * Tests how {@link SingleNodeConsensusResultImpl} returns the pre-consensus and stale events of a
 * {@link NodeResultsCollector}.
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
    void eventsAreUnsupportedIfTheCollectorDoesNotCollectThem() {
        final NodeResultsCollector collector = new NodeResultsCollector(NODE_ID, new ConsensusRoundPool(), false);
        final SingleNodeConsensusResult result = collector.newConsensusResult();

        result.clear();

        assertThat(result.isCleared()).isTrue();
        assertThatThrownBy(result::preConsensusEvents).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(result::staleEvents).isInstanceOf(UnsupportedOperationException.class);
    }

    @NonNull
    private EventDescriptorWrapper event() {
        final byte[] hash = ByteBuffer.allocate(DigestType.SHA_384.digestLength())
                .putInt(nextHash++)
                .array();
        return new EventDescriptorWrapper(new Hash(hash, DigestType.SHA_384), NODE_ID, 1L);
    }
}
