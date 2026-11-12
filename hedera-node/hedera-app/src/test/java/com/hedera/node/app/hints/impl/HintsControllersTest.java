// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsControllersTest {
    private static final HintsConstruction ONE_CONSTRUCTION = HintsConstruction.newBuilder()
            .constructionId(1L)
            .crsId(1L)
            .numParties(8)
            .build();
    private static final Roster CURRENT_ROSTER = new Roster(List.of(
            RosterEntry.newBuilder().nodeId(1L).build(),
            RosterEntry.newBuilder().nodeId(2L).build()));
    private static final PreprocessingVote VOTE = PreprocessingVote.newBuilder()
            .preprocessedKeys(new PreprocessedKeys(Bytes.wrap("AK"), Bytes.wrap("VK")))
            .build();

    @Mock
    private Executor executor;

    @Mock
    private HintsKeyAccessor keyAccessor;

    @Mock
    private NodeInfo selfNodeInfo;

    @Mock
    private HintsLibrary library;

    @Mock
    private HintsSubmissions submissions;

    @Mock
    private Supplier<NodeInfo> selfNodeInfoSupplier;

    @Mock
    private ActiveRosters activeRosters;

    @Mock
    private RosterTransitionWeights weights;

    @Mock
    private WritableHintsStore hintsStore;

    @Mock
    private HintsContext context;

    @Mock
    private OnHintsFinished onHintsFinished;

    @Mock
    private HintsController controller;

    @Mock
    private ReadableRosterStore rosterStore;

    private HintsControllers subject;

    @BeforeEach
    void setUp() {
        subject = new HintsControllers(
                executor,
                keyAccessor,
                library,
                submissions,
                context,
                selfNodeInfoSupplier,
                HederaTestConfigBuilder::createConfig,
                onHintsFinished);
    }

    @Test
    void getsAndCreatesInertControllersAsExpected() {
        given(activeRosters.transitionWeights(null)).willReturn(weights);

        final var twoConstruction =
                HintsConstruction.newBuilder().constructionId(2L).build();

        assertTrue(subject.getInProgressById(2L).isEmpty());
        final var firstController =
                subject.getOrCreateFor(activeRosters, ONE_CONSTRUCTION, hintsStore, HintsConstruction.DEFAULT);
        assertTrue(subject.getInProgressById(1L).isEmpty());
        assertTrue(subject.getInProgressById(2L).isEmpty());
        assertInstanceOf(InertHintsController.class, firstController);
        final var secondController =
                subject.getOrCreateFor(activeRosters, twoConstruction, hintsStore, HintsConstruction.DEFAULT);
        assertNotSame(firstController, secondController);
        assertInstanceOf(InertHintsController.class, secondController);
    }

    @Test
    void returnsActiveControllerWhenSourceNodesHaveTargetThresholdWeight() {
        given(activeRosters.transitionWeights(null)).willReturn(weights);
        given(weights.sourceNodesHaveTargetThreshold()).willReturn(true);
        given(keyAccessor.getOrCreateBlsPrivateKey(1L)).willReturn(Bytes.EMPTY);
        given(selfNodeInfoSupplier.get()).willReturn(selfNodeInfo);
        given(hintsStore.getCrsStateFor(ONE_CONSTRUCTION)).willReturn(CRSState.DEFAULT);
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(Set.of(1L)));
        given(activeRosters.currentRoster()).willReturn(CURRENT_ROSTER);
        given(hintsStore.getVotes(1L, Set.of(1L, 2L))).willReturn(Map.of());

        final var controller =
                subject.getOrCreateFor(activeRosters, ONE_CONSTRUCTION, hintsStore, HintsConstruction.DEFAULT);

        assertInstanceOf(HintsControllerImpl.class, controller);
        verify(hintsStore).getVotes(1L, Set.of(1L, 2L));
    }

    @Test
    void stopCancelsAndRefreshesControllerForSameConstructionId() throws Exception {
        final var learnedConstruction = HintsConstruction.newBuilder()
                .constructionId(1L)
                .preprocessingStartTime(asTimestamp(Instant.EPOCH))
                .build();
        given(controller.constructionId()).willReturn(1L);
        setController(controller);

        final var staleController =
                subject.getOrCreateFor(activeRosters, learnedConstruction, hintsStore, HintsConstruction.DEFAULT);
        assertSame(controller, staleController);

        subject.stop();
        subject.stop();

        verify(controller).cancelPendingWork();
        given(activeRosters.transitionWeights(null)).willReturn(weights);
        final var refreshedController =
                subject.getOrCreateFor(activeRosters, learnedConstruction, hintsStore, HintsConstruction.DEFAULT);
        assertNotSame(staleController, refreshedController);
    }

    @Test
    void sourceNodesMissingFromActiveSchemeStillCountTowardsVoteThreshold() {
        final var votingController = votingControllerWithSchemePartyWeight(10L);

        // Three nodes hold 30 of the source roster's 100 weight, which is less than one third
        for (long nodeId = 0; nodeId < 3; nodeId++) {
            assertTrue(votingController.addPreprocessingVote(nodeId, VOTE, hintsStore));
        }
        verify(hintsStore, never()).setHintsScheme(eq(2L), any(), any(), any());

        // A fourth node brings the votes to 40, which is at least one third
        assertTrue(votingController.addPreprocessingVote(3L, VOTE, hintsStore));
        verify(hintsStore).setHintsScheme(eq(2L), eq(VOTE.preprocessedKeysOrThrow()), any(), any());
    }

    @Test
    void activeSchemeWeightsStillDecideVoteWeightAfterWeightRotation() {
        // The scheme's seven parties now weigh 20 each, so the total is 140 plus the 30 of the three other nodes
        final var votingController = votingControllerWithSchemePartyWeight(20L);

        // Two nodes hold 40 of that 170 weight, which is less than one third
        for (long nodeId = 0; nodeId < 2; nodeId++) {
            assertTrue(votingController.addPreprocessingVote(nodeId, VOTE, hintsStore));
        }
        verify(hintsStore, never()).setHintsScheme(eq(2L), any(), any(), any());

        // A third node brings the votes to 60, which is at least one third
        assertTrue(votingController.addPreprocessingVote(2L, VOTE, hintsStore));
        verify(hintsStore).setHintsScheme(eq(2L), eq(VOTE.preprocessedKeysOrThrow()), any(), any());
    }

    /**
     * Returns the controller for a construction in a network of ten nodes with roster weight 10, where
     * nodes 7, 8, and 9 hold no party in the active hinTS scheme and every other node holds a party with
     * the given weight.
     */
    private HintsController votingControllerWithSchemePartyWeight(final long partyWeight) {
        final var sourceRosterHash = Bytes.wrap("source");
        final var targetRosterHash = Bytes.wrap("target");
        final var roster = new Roster(LongStream.range(0, 10)
                .mapToObj(nodeId ->
                        RosterEntry.newBuilder().nodeId(nodeId).weight(10L).build())
                .toList());
        given(rosterStore.getActiveRosterHash()).willReturn(sourceRosterHash);
        given(rosterStore.getCandidateRosterHash()).willReturn(targetRosterHash);
        given(rosterStore.get(sourceRosterHash)).willReturn(roster);
        given(rosterStore.get(targetRosterHash)).willReturn(roster);
        final var rosters = ActiveRosters.from(rosterStore, false, () -> false, null);
        final var activeConstruction = HintsConstruction.newBuilder()
                .constructionId(1L)
                .hintsScheme(new HintsScheme(
                        PreprocessedKeys.DEFAULT,
                        LongStream.range(0, 7)
                                .mapToObj(nodeId -> new NodePartyId(nodeId, (int) nodeId + 1, partyWeight))
                                .toList()))
                .build();
        final var nextConstruction = HintsConstruction.newBuilder()
                .constructionId(2L)
                .crsId(1L)
                .numParties(16)
                .preprocessingStartTime(asTimestamp(Instant.EPOCH))
                .build();
        given(keyAccessor.getOrCreateBlsPrivateKey(2L)).willReturn(Bytes.EMPTY);
        given(selfNodeInfoSupplier.get()).willReturn(selfNodeInfo);
        given(hintsStore.getCrsStateFor(nextConstruction))
                .willReturn(CRSState.newBuilder().stage(CRSStage.COMPLETED).build());
        lenient()
                .when(hintsStore.setHintsScheme(eq(2L), eq(VOTE.preprocessedKeysOrThrow()), any(), any()))
                .thenReturn(nextConstruction
                        .copyBuilder()
                        .hintsScheme(new HintsScheme(VOTE.preprocessedKeysOrThrow(), List.of()))
                        .build());
        return subject.getOrCreateFor(rosters, nextConstruction, hintsStore, activeConstruction);
    }

    private void setController(final HintsController controller) throws Exception {
        final var field = HintsControllers.class.getDeclaredField("controller");
        field.setAccessible(true);
        field.set(subject, controller);
    }
}
