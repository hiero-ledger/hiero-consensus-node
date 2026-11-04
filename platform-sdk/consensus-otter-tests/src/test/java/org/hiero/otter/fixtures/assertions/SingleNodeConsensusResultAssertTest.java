// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.internal.result.MultipleNodeConsensusResultsImpl;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.junit.jupiter.api.Test;

/**
 * Tests the assertions of {@link SingleNodeConsensusResultAssert} and {@link MultipleNodeConsensusResultsAssert} that
 * check the pre-consensus and stale events.
 */
class SingleNodeConsensusResultAssertTest {

    private static final NodeId NODE_0 = NodeId.of(0L);
    private static final NodeId NODE_1 = NodeId.of(1L);

    /** Events with a birth round below this threshold are ancient. */
    private static final long ANCIENT_THRESHOLD = 10L;

    private static final EventWindow EVENT_WINDOW = new EventWindow(20L, 21L, ANCIENT_THRESHOLD, 1L);

    private int nextHash = 0;

    @Test
    void hasNoLostEvents_passesIfEveryAncientEventReachedConsensusOrBecameStale() {
        final EventDescriptorWrapper consensusEvent = ancientEvent();
        final EventDescriptorWrapper staleEvent = ancientEvent();
        final SingleNodeConsensusResult result = result(
                NODE_0, List.of(round(consensusEvent)), List.of(consensusEvent, staleEvent), List.of(staleEvent));

        assertThatCode(() -> assertThat(result).hasNoLostEvents()).doesNotThrowAnyException();
    }

    @Test
    void hasNoLostEvents_ignoresNonAncientEvents() {
        final EventDescriptorWrapper pendingEvent = nonAncientEvent();
        final SingleNodeConsensusResult result = result(NODE_0, List.of(), List.of(pendingEvent), List.of());

        assertThatCode(() -> assertThat(result).hasNoLostEvents()).doesNotThrowAnyException();
    }

    @Test
    void hasNoLostEvents_failsIfAncientEventReachedNeither() {
        final EventDescriptorWrapper lostEvent = ancientEvent();
        final SingleNodeConsensusResult result = result(NODE_0, List.of(), List.of(lostEvent), List.of());

        assertThatThrownBy(() -> assertThat(result).hasNoLostEvents())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("node 0")
                .hasMessageContaining("1 events did neither and 0 events did both")
                .hasMessageContaining("Neither: [" + lostEvent + "]");
    }

    @Test
    void hasNoLostEvents_failsIfAncientEventReachedBoth() {
        final EventDescriptorWrapper event = ancientEvent();
        final SingleNodeConsensusResult result = result(NODE_0, List.of(round(event)), List.of(event), List.of(event));

        assertThatThrownBy(() -> assertThat(result).hasNoLostEvents())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("0 events did neither and 1 events did both")
                .hasMessageContaining("Both: [" + event + "]");
    }

    @Test
    void hasNoLostEvents_failsIfNotAllResultsArrived() {
        final SingleNodeConsensusResult result = new FakeResult(NODE_0, List.of(), List.of(), List.of(), false, false);

        assertThatThrownBy(() -> assertThat(result).hasNoLostEvents())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cannot check node 0 for lost events")
                .hasMessageContaining("not all of its results have arrived");
    }

    @Test
    void hasNoUnannouncedEvents_passesIfEveryConsensusAndStaleEventWasPreConsensus() {
        final EventDescriptorWrapper consensusEvent = ancientEvent();
        final EventDescriptorWrapper staleEvent = ancientEvent();
        final EventDescriptorWrapper pendingEvent = nonAncientEvent();
        final SingleNodeConsensusResult result = result(
                NODE_0,
                List.of(round(consensusEvent)),
                List.of(consensusEvent, staleEvent, pendingEvent),
                List.of(staleEvent));

        assertThatCode(() -> assertThat(result).hasNoUnannouncedEvents()).doesNotThrowAnyException();
    }

    @Test
    void hasNoUnannouncedEvents_failsIfConsensusEventWasNotPreConsensus() {
        final EventDescriptorWrapper event = ancientEvent();
        final SingleNodeConsensusResult result = result(NODE_0, List.of(round(event)), List.of(), List.of());

        assertThatThrownBy(() -> assertThat(result).hasNoUnannouncedEvents())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("node 0")
                .hasMessageContaining("1 consensus events and 0 stale events were not")
                .hasMessageContaining("Consensus events: [" + event + "]");
    }

    @Test
    void hasNoUnannouncedEvents_failsIfStaleEventWasNotPreConsensus() {
        final EventDescriptorWrapper event = ancientEvent();
        final SingleNodeConsensusResult result = result(NODE_0, List.of(), List.of(), List.of(event));

        assertThatThrownBy(() -> assertThat(result).hasNoUnannouncedEvents())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("0 consensus events and 1 stale events were not")
                .hasMessageContaining("Stale events: [" + event + "]");
    }

    @Test
    void hasNoUnannouncedEvents_failsIfResultWasCleared() {
        final SingleNodeConsensusResult result = new FakeResult(NODE_0, List.of(), List.of(), List.of(), true, true);

        assertThatThrownBy(() -> assertThat(result).hasNoUnannouncedEvents())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cannot check node 0 for unannounced events")
                .hasMessageContaining("clear() has been called");
    }

    @Test
    void hasNoUnannouncedEvents_failsIfNotAllResultsArrived() {
        final SingleNodeConsensusResult result = new FakeResult(NODE_0, List.of(), List.of(), List.of(), false, false);

        assertThatThrownBy(() -> assertThat(result).hasNoUnannouncedEvents())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cannot check node 0 for unannounced events")
                .hasMessageContaining("not all of its results have arrived");
    }

    @Test
    void haveNoLostEvents_failsIfOneNodeLostAnEvent() {
        final SingleNodeConsensusResult goodResult = result(NODE_0, List.of(), List.of(), List.of());
        final SingleNodeConsensusResult badResult = result(NODE_1, List.of(), List.of(ancientEvent()), List.of());
        final MultipleNodeConsensusResults results =
                new MultipleNodeConsensusResultsImpl(List.of(goodResult, badResult));

        assertThatThrownBy(() -> assertThat(results).haveNoLostEvents())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("node 1");
    }

    @Test
    void haveNoUnannouncedEvents_failsIfOneNodeHasAnUnannouncedEvent() {
        final SingleNodeConsensusResult goodResult = result(NODE_0, List.of(), List.of(), List.of());
        final SingleNodeConsensusResult badResult = result(NODE_1, List.of(), List.of(), List.of(ancientEvent()));
        final MultipleNodeConsensusResults results =
                new MultipleNodeConsensusResultsImpl(List.of(goodResult, badResult));

        assertThatThrownBy(() -> assertThat(results).haveNoUnannouncedEvents())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("node 1");
    }

    @NonNull
    private EventDescriptorWrapper ancientEvent() {
        return event(ANCIENT_THRESHOLD - 1);
    }

    @NonNull
    private EventDescriptorWrapper nonAncientEvent() {
        return event(ANCIENT_THRESHOLD);
    }

    @NonNull
    private EventDescriptorWrapper event(final long birthRound) {
        final byte[] hash = ByteBuffer.allocate(DigestType.SHA_384.digestLength())
                .putInt(nextHash++)
                .array();
        return new EventDescriptorWrapper(new Hash(hash, DigestType.SHA_384), NODE_0, birthRound);
    }

    @NonNull
    private static ConsensusRound round(@NonNull final EventDescriptorWrapper... events) {
        final List<PlatformEvent> consensusEvents = Arrays.stream(events)
                .map(descriptor -> {
                    final PlatformEvent event = mock(PlatformEvent.class);
                    when(event.getDescriptor()).thenReturn(descriptor);
                    return event;
                })
                .toList();
        final ConsensusRound round = mock(ConsensusRound.class);
        when(round.getConsensusEvents()).thenReturn(consensusEvents);
        return round;
    }

    @NonNull
    private static SingleNodeConsensusResult result(
            @NonNull final NodeId nodeId,
            @NonNull final List<ConsensusRound> consensusRounds,
            @NonNull final List<EventDescriptorWrapper> preConsensusEvents,
            @NonNull final List<EventDescriptorWrapper> staleEvents) {
        return new FakeResult(nodeId, consensusRounds, preConsensusEvents, staleEvents, false, true);
    }

    /**
     * A {@link SingleNodeConsensusResult} with fixed content. Its latest event window is always {@link #EVENT_WINDOW}.
     */
    private record FakeResult(
            @NonNull NodeId nodeId,
            @NonNull List<ConsensusRound> consensusRounds,
            @NonNull List<EventDescriptorWrapper> preConsensusEvents,
            @NonNull List<EventDescriptorWrapper> staleEvents,
            boolean isCleared,
            boolean allResultsArrived)
            implements SingleNodeConsensusResult {

        @Override
        @NonNull
        public EventWindow getLatestEventWindow() {
            return EVENT_WINDOW;
        }

        @Override
        public void subscribe(@NonNull final ConsensusRoundSubscriber subscriber) {
            // the content is fixed, so there is nothing to notify about
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("The content of a FakeResult is fixed");
        }
    }
}
