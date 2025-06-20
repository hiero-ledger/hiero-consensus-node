// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.roster;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActiveRostersTest {
    private static final long MISSING_NODE_ID = 666L;
    private static final List<RosterTransitionWeights.NodeWeight> A_ROSTER_NODE_WEIGHTS = List.of(
            new RosterTransitionWeights.NodeWeight(1L, 1L),
            new RosterTransitionWeights.NodeWeight(2L, 2L),
            new RosterTransitionWeights.NodeWeight(3L, 3L),
            new RosterTransitionWeights.NodeWeight(4L, 0L));
    private static final List<RosterTransitionWeights.NodeWeight> B_ROSTER_NODE_WEIGHTS = List.of(
            new RosterTransitionWeights.NodeWeight(1L, 2L),
            new RosterTransitionWeights.NodeWeight(2L, 4L),
            new RosterTransitionWeights.NodeWeight(3L, 6L));
    private static final Map<Long, Long> A_ROSTER_WEIGHTS = A_ROSTER_NODE_WEIGHTS.stream()
            .collect(Collectors.toMap(
                    RosterTransitionWeights.NodeWeight::nodeId, RosterTransitionWeights.NodeWeight::weight));
    private static final Map<Long, Long> B_ROSTER_WEIGHTS = B_ROSTER_NODE_WEIGHTS.stream()
            .collect(Collectors.toMap(
                    RosterTransitionWeights.NodeWeight::nodeId, RosterTransitionWeights.NodeWeight::weight));
    private static final Bytes A_ROSTER_HASH = Bytes.wrap("A_ROSTER_HASH");
    private static final Bytes B_ROSTER_HASH = Bytes.wrap("B_ROSTER_HASH");
    private static final Roster A_ROSTER = new Roster(A_ROSTER_WEIGHTS.entrySet().stream()
            .map(entry -> new RosterEntry(entry.getKey(), entry.getValue(), Bytes.EMPTY, List.of()))
            .toList());
    private static final Roster B_ROSTER = new Roster(B_ROSTER_WEIGHTS.entrySet().stream()
            .map(entry -> new RosterEntry(entry.getKey(), entry.getValue(), Bytes.EMPTY, List.of()))
            .toList());
    private static final long A_ROSTER_STRONG_MINORITY_WEIGHT = RosterTransitionWeights.atLeastOneThirdOfTotal(
            A_ROSTER_WEIGHTS.values().stream().mapToLong(Long::longValue).sum());
    private static final long A_ROSTER_STRICT_MAJORITY_WEIGHT = RosterTransitionWeights.moreThanTwoThirdsOfTotal(
            A_ROSTER_WEIGHTS.values().stream().mapToLong(Long::longValue).sum());
    private static final long B_ROSTER_STRICT_MAJORITY_WEIGHT = RosterTransitionWeights.moreThanTwoThirdsOfTotal(
            B_ROSTER_WEIGHTS.values().stream().mapToLong(Long::longValue).sum());

    @Mock
    private ReadableRosterStore rosterStore;

    @Test
    void detectsBootstrap() {
        given(rosterStore.getCurrentRosterHash()).willReturn(A_ROSTER_HASH);
        given(rosterStore.get(A_ROSTER_HASH)).willReturn(A_ROSTER);

        final var activeRosters = ActiveRosters.from(rosterStore);

        assertEquals(ActiveRosters.Phase.BOOTSTRAP, activeRosters.phase());
        assertEquals(A_ROSTER_HASH, activeRosters.currentRosterHash());
        assertEquals(A_ROSTER_HASH, activeRosters.sourceRosterHash());
        assertEquals(A_ROSTER_HASH, activeRosters.targetRosterHash());
        assertSame(A_ROSTER, activeRosters.findRelatedRoster(A_ROSTER_HASH));
        assertSame(A_ROSTER, activeRosters.targetRoster());
        final var weights = activeRosters.transitionWeights();
        assertEquals(A_ROSTER_WEIGHTS, weights.sourceNodeWeights());
        assertEquals(A_ROSTER_WEIGHTS, weights.targetNodeWeights());
        assertEquals(A_ROSTER_STRONG_MINORITY_WEIGHT, weights.sourceWeightThreshold());
        assertEquals(A_ROSTER_STRICT_MAJORITY_WEIGHT, weights.targetWeightThreshold());
        assertEquals(A_ROSTER_NODE_WEIGHTS, weights.orderedSourceWeights().toList());
        assertEquals(A_ROSTER_NODE_WEIGHTS, weights.orderedTargetWeights().toList());
        assertTrue(A_ROSTER_NODE_WEIGHTS.stream().allMatch(nw -> weights.sourceWeightOf(nw.nodeId()) == nw.weight()));
        assertTrue(A_ROSTER_NODE_WEIGHTS.stream().allMatch(nw -> weights.targetWeightOf(nw.nodeId()) == nw.weight()));
        assertTrue(A_ROSTER_NODE_WEIGHTS.stream().allMatch(nw -> weights.targetIncludes(nw.nodeId())));
        assertEquals(A_ROSTER.rosterEntries().size(), weights.numTargetNodesIn(A_ROSTER_WEIGHTS.keySet()));
        assertEquals(
                1,
                weights.numTargetNodesIn(
                        Set.of(A_ROSTER_WEIGHTS.keySet().iterator().next())));
        assertEquals(0L, weights.sourceWeightOf(MISSING_NODE_ID));
        assertEquals(0L, weights.targetWeightOf(MISSING_NODE_ID));
    }

    @Test
    void detectsHandoff() {
        given(rosterStore.getCurrentRosterHash()).willReturn(A_ROSTER_HASH);
        given(rosterStore.getPreviousRosterHash()).willReturn(B_ROSTER_HASH);
        given(rosterStore.get(A_ROSTER_HASH)).willReturn(A_ROSTER);

        final var activeRosters = ActiveRosters.from(rosterStore);

        assertEquals(ActiveRosters.Phase.HANDOFF, activeRosters.phase());
        assertEquals(A_ROSTER_HASH, activeRosters.currentRosterHash());
        assertThrows(IllegalStateException.class, activeRosters::targetRoster);
        assertThrows(IllegalStateException.class, activeRosters::sourceRosterHash);
        assertThrows(IllegalStateException.class, activeRosters::targetRosterHash);
        assertSame(A_ROSTER, activeRosters.findRelatedRoster(A_ROSTER_HASH));
        assertThrows(IllegalStateException.class, activeRosters::transitionWeights);
        assertThrows(IllegalStateException.class, activeRosters::removedNodeIds);
    }

    @Test
    void detectsTransition() {
        given(rosterStore.getCurrentRosterHash()).willReturn(A_ROSTER_HASH);
        given(rosterStore.getCandidateRosterHash()).willReturn(B_ROSTER_HASH);
        given(rosterStore.get(A_ROSTER_HASH)).willReturn(A_ROSTER);
        given(rosterStore.get(B_ROSTER_HASH)).willReturn(B_ROSTER);

        final var activeRosters = ActiveRosters.from(rosterStore);

        final var removedNodeIds = activeRosters.removedNodeIds().stream().toList();
        assertEquals(List.of(4L), removedNodeIds);
        assertEquals(ActiveRosters.Phase.TRANSITION, activeRosters.phase());
        assertEquals(A_ROSTER_HASH, activeRosters.currentRosterHash());
        assertEquals(A_ROSTER_HASH, activeRosters.sourceRosterHash());
        assertEquals(B_ROSTER_HASH, activeRosters.targetRosterHash());
        assertSame(A_ROSTER, activeRosters.findRelatedRoster(A_ROSTER_HASH));
        assertSame(B_ROSTER, activeRosters.targetRoster());
        final var weights = activeRosters.transitionWeights();
        assertEquals(A_ROSTER_WEIGHTS, weights.sourceNodeWeights());
        assertEquals(B_ROSTER_WEIGHTS, weights.targetNodeWeights());
        assertEquals(A_ROSTER_STRONG_MINORITY_WEIGHT, weights.sourceWeightThreshold());
        assertEquals(B_ROSTER_STRICT_MAJORITY_WEIGHT, weights.targetWeightThreshold());
        assertEquals(A_ROSTER_NODE_WEIGHTS, weights.orderedSourceWeights().toList());
        assertEquals(B_ROSTER_NODE_WEIGHTS, weights.orderedTargetWeights().toList());
        assertTrue(A_ROSTER_NODE_WEIGHTS.stream().allMatch(nw -> weights.sourceWeightOf(nw.nodeId()) == nw.weight()));
        assertTrue(B_ROSTER_NODE_WEIGHTS.stream().allMatch(nw -> weights.targetWeightOf(nw.nodeId()) == nw.weight()));
        assertTrue(B_ROSTER_NODE_WEIGHTS.stream().allMatch(nw -> weights.targetIncludes(nw.nodeId())));
        final var allNodeIds = B_ROSTER_WEIGHTS.keySet().stream().toList();
        final var someNodeIds = Set.copyOf(allNodeIds.subList(1, allNodeIds.size()));
        assertEquals(B_ROSTER.rosterEntries().size() - 1, weights.numTargetNodesIn(someNodeIds));
        assertEquals(0L, weights.sourceWeightOf(MISSING_NODE_ID));
        assertEquals(0L, weights.targetWeightOf(MISSING_NODE_ID));
    }
}
