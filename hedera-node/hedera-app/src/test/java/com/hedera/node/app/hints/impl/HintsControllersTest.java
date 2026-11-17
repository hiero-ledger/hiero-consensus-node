// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.ProofNodeWeight;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final Bytes GENERATION_HASH = Bytes.wrap("active-tss-generation");
    private static final PreprocessedKeys SOURCE_KEYS =
            new PreprocessedKeys(Bytes.wrap("source-ak"), Bytes.wrap("source-vk"));

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
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        configuration = HederaTestConfigBuilder.createConfig();
        subject = new HintsControllers(
                executor,
                keyAccessor,
                library,
                submissions,
                context,
                selfNodeInfoSupplier,
                () -> configuration,
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
        // All ten generation members weigh 20, even though the platform roster still lists 10 each.
        final var votingController = votingControllerWithSchemePartyWeight(20L);

        // Three nodes hold 60 of the generation's 200 weight, which is less than one third.
        for (long nodeId = 0; nodeId < 3; nodeId++) {
            assertTrue(votingController.addPreprocessingVote(nodeId, VOTE, hintsStore));
        }
        verify(hintsStore, never()).setHintsScheme(eq(2L), any(), any(), any());

        assertTrue(votingController.addPreprocessingVote(3L, VOTE, hintsStore));
        verify(hintsStore).setHintsScheme(eq(2L), eq(VOTE.preprocessedKeysOrThrow()), any(), any());
    }

    @Test
    void decreasingGenerationWeightsDoNotMakeAdoptionUnreachable() {
        final var votingController = votingControllerWithSchemePartyWeight(1L);

        // The complete generation weighs 10, so four eligible votes suffice. Mixing seven scheme
        // weights with three stale platform weights would require 13, above all seven eligible votes.
        for (long nodeId = 0; nodeId < 3; nodeId++) {
            assertTrue(votingController.addPreprocessingVote(nodeId, VOTE, hintsStore));
        }
        verify(hintsStore, never()).setHintsScheme(eq(2L), any(), any(), any());

        assertTrue(votingController.addPreprocessingVote(3L, VOTE, hintsStore));
        verify(hintsStore).setHintsScheme(eq(2L), eq(VOTE.preprocessedKeysOrThrow()), any(), any());
    }

    @Test
    void omittedGenerationMembersDoNotContributeVoteWeight() {
        final var votingController = votingControllerWithSchemePartyWeight(1L);

        assertTrue(votingController.addPreprocessingVote(7L, VOTE, hintsStore));
        assertTrue(votingController.addPreprocessingVote(8L, VOTE, hintsStore));
        assertTrue(votingController.addPreprocessingVote(9L, VOTE, hintsStore));
        for (long nodeId = 0; nodeId < 3; nodeId++) {
            assertTrue(votingController.addPreprocessingVote(nodeId, VOTE, hintsStore));
        }
        verify(hintsStore, never()).setHintsScheme(eq(2L), any(), any(), any());

        assertTrue(votingController.addPreprocessingVote(3L, VOTE, hintsStore));
        verify(hintsStore).setHintsScheme(eq(2L), eq(VOTE.preprocessedKeysOrThrow()), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,true"})
    void compatibilityModesUseTheLegacySchemeVoteThresholdWithoutHistory(
            final boolean historyEnabled, final boolean forceMockSignatures) {
        configuration = HederaTestConfigBuilder.create()
                .withValue("tss.historyEnabled", historyEnabled)
                .withValue("tss.forceMockSignatures", forceMockSignatures)
                .getOrCreateConfig();
        final var controller = votingControllerWithSchemePartyWeight(1L, false);

        assertTrue(controller.addPreprocessingVote(0, VOTE, hintsStore));
        assertTrue(controller.addPreprocessingVote(1, VOTE, hintsStore));
        verify(hintsStore, never()).setHintsScheme(eq(2L), any(), any(), any());
        assertTrue(controller.addPreprocessingVote(2, VOTE, hintsStore));

        verify(hintsStore).setHintsScheme(eq(2L), eq(VOTE.preprocessedKeysOrThrow()), any(), any());
    }

    @Test
    void completedSourceSchemeRequiresMatchingHistorySnapshot() {
        final var activeHints = sourceConstruction(1L);
        final var activeHistory = sourceHistory(1L);

        assertThrows(
                HintsGenerationWeights.InvalidGenerationException.class,
                () -> subject.getOrCreateFor(activeRosters, ONE_CONSTRUCTION, hintsStore, activeHints));
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.getOrCreateFor(
                        activeRosters,
                        ONE_CONSTRUCTION,
                        hintsStore,
                        activeHints,
                        activeHistory
                                .copyBuilder()
                                .targetRosterHash(Bytes.wrap("other-generation"))
                                .build()));
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.getOrCreateFor(
                        activeRosters,
                        ONE_CONSTRUCTION,
                        hintsStore,
                        activeHints,
                        activeHistory
                                .copyBuilder()
                                .targetProof(activeHistory
                                        .targetProofOrThrow()
                                        .copyBuilder()
                                        .targetHistory(new History(Bytes.EMPTY, Bytes.wrap("other-key")))
                                        .build())
                                .build()));
    }

    @Test
    void equalReparsedSourceRecordsReuseTheResolvedGenerationAndController() throws Exception {
        final var activeHints = sourceConstruction(1L);
        final var history = sourceHistory(1L);
        final var hintsBytes = HintsConstruction.PROTOBUF.toBytes(activeHints);
        final var historyBytes = HistoryProofConstruction.PROTOBUF.toBytes(history);
        final var firstHints = HintsConstruction.PROTOBUF.parse(hintsBytes);
        final var secondHints = HintsConstruction.PROTOBUF.parse(hintsBytes);
        final var firstHistory = HistoryProofConstruction.PROTOBUF.parse(historyBytes);
        final var secondHistory = HistoryProofConstruction.PROTOBUF.parse(historyBytes);
        assertNotSame(firstHints, secondHints);
        assertNotSame(firstHistory, secondHistory);
        assertEquals(firstHints, secondHints);
        assertEquals(firstHistory, secondHistory);
        final var upcoming = ONE_CONSTRUCTION.copyBuilder().constructionId(2L).build();
        final var upcomingBytes = HintsConstruction.PROTOBUF.toBytes(upcoming);
        given(activeRosters.transitionWeights(any())).willReturn(weights);
        given(weights.targetWeightThreshold()).willReturn(1L);
        try (final var generationFactory = mockStatic(HintsGenerationWeights.class, CALLS_REAL_METHODS)) {
            final var first = subject.getOrCreateFor(
                    activeRosters,
                    HintsConstruction.PROTOBUF.parse(upcomingBytes),
                    hintsStore,
                    firstHints,
                    firstHistory);
            final var second = subject.getOrCreateFor(
                    activeRosters,
                    HintsConstruction.PROTOBUF.parse(upcomingBytes),
                    hintsStore,
                    secondHints,
                    secondHistory);

            assertSame(first, second);
            generationFactory.verify(() -> HintsGenerationWeights.from(firstHints, firstHistory));
            verify(activeRosters).transitionWeights(any());
            final var changedSnapshot = history.targetProofOrThrow().targetNodeWeights().stream()
                    .map(entry -> entry.nodeId() == 9L ? new ProofNodeWeight(9L, 2L) : entry)
                    .toList();
            final var changedHistory = HistoryProofConstruction.PROTOBUF.parse(
                    HistoryProofConstruction.PROTOBUF.toBytes(history.copyBuilder()
                            .targetProof(
                                    history.targetProofOrThrow().copyBuilder().targetNodeWeights(changedSnapshot))
                            .build()));

            final var changed =
                    subject.getOrCreateFor(activeRosters, upcoming, hintsStore, secondHints, changedHistory);

            assertNotSame(
                    first, changed, "A changed full source book must rebuild even when source IDs and keys match");
            generationFactory.verify(() -> HintsGenerationWeights.from(secondHints, changedHistory));
            verify(activeRosters, times(2)).transitionWeights(any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidSourceCancelsCachedControllerAndRecoveryRebuildsIt(final boolean missingHistory) {
        try (final var generationFactory = mockStatic(HintsGenerationWeights.class, CALLS_REAL_METHODS)) {
            final var oldController = votingControllerWithSchemePartyWeight(1L);
            assertSame(oldController, subject.getInProgressById(2L).orElseThrow());
            final var invalidHistory = missingHistory
                    ? null
                    : sourceHistory(1L)
                            .copyBuilder()
                            .targetRosterHash(Bytes.wrap("another generation"))
                            .build();

            assertThrows(
                    HintsGenerationWeights.InvalidGenerationException.class,
                    () -> subject.getOrCreateFor(
                            activeRosters,
                            HintsConstruction.newBuilder().constructionId(2L).build(),
                            hintsStore,
                            sourceConstruction(1L),
                            invalidHistory));

            assertTrue(subject.getAnyInProgress().isEmpty());
            assertTrue(subject.getInProgressById(2L).isEmpty());
            assertFalse(oldController.isStillInProgress());
            assertFalse(oldController.addPreprocessingVote(0L, VOTE, hintsStore));
            verify(hintsStore, never()).addPreprocessingVote(anyLong(), anyLong(), any());
            verify(hintsStore, never()).setHintsScheme(anyLong(), any(), any(), any());

            final var recoveredController = votingControllerWithSchemePartyWeight(1L);
            assertNotSame(oldController, recoveredController);
            assertSame(recoveredController, subject.getInProgressById(2L).orElseThrow());
            assertTrue(recoveredController.addPreprocessingVote(0L, VOTE, hintsStore));
            generationFactory.verify(
                    () -> HintsGenerationWeights.from(sourceConstruction(1L), sourceHistory(1L)), times(2));
        }
    }

    /**
     * Returns the controller for a construction in a network of ten nodes whose platform weights are
     * still 10 but whose committed generation gives every node the requested weight. Nodes 7, 8, and 9
     * hold no party in the active hinTS scheme.
     */
    private HintsController votingControllerWithSchemePartyWeight(final long partyWeight) {
        return votingControllerWithSchemePartyWeight(partyWeight, true);
    }

    private HintsController votingControllerWithSchemePartyWeight(final long partyWeight, final boolean withHistory) {
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
        final var activeConstruction = sourceConstruction(partyWeight);
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
        return withHistory
                ? subject.getOrCreateFor(
                        rosters, nextConstruction, hintsStore, activeConstruction, sourceHistory(partyWeight))
                : subject.getOrCreateFor(rosters, nextConstruction, hintsStore, activeConstruction);
    }

    private static HintsConstruction sourceConstruction(final long weight) {
        return HintsConstruction.newBuilder()
                .constructionId(1L)
                .targetRosterHash(GENERATION_HASH)
                .hintsScheme(new HintsScheme(
                        SOURCE_KEYS,
                        LongStream.range(0, 7)
                                .mapToObj(nodeId -> new NodePartyId(nodeId, (int) nodeId + 1, weight))
                                .toList()))
                .build();
    }

    private static HistoryProofConstruction sourceHistory(final long weight) {
        return HistoryProofConstruction.newBuilder()
                .targetRosterHash(GENERATION_HASH)
                .targetProof(HistoryProof.newBuilder()
                        .targetHistory(new History(Bytes.EMPTY, SOURCE_KEYS.verificationKey()))
                        .targetNodeWeights(LongStream.range(0, 10)
                                .mapToObj(nodeId -> new ProofNodeWeight(nodeId, weight))
                                .toList())
                        .build())
                .build();
    }

    private void setController(final HintsController controller) throws Exception {
        final var field = HintsControllers.class.getDeclaredField("controller");
        field.setAccessible(true);
        field.set(subject, controller);
    }
}
