// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.hints.HintsService.partySizeForRoster;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_LABEL;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_LABEL;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_LABEL;
import static com.hedera.node.app.hints.schemas.V079HintsSchema.NEXT_CRS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema.HIGHEST_NODE_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema.HIGHEST_NODE_ID_STATE_LABEL;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.BOOTSTRAP;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.HANDOFF;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.TRANSITION;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.hints.PreprocessingVoteId;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.schemas.V059HintsSchema;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.service.entityid.WritableEntityIdStore;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableHintsStoreImplTest {

    private static final Metrics NO_OP_METRICS = new NoOpMetrics();
    private static final PreprocessingVote DEFAULT_VOTE = PreprocessingVote.newBuilder()
            .preprocessedKeys(PreprocessedKeys.DEFAULT)
            .build();
    private static final Roster A_ROSTER = new Roster(List.of(RosterEntry.DEFAULT));
    private static final Bytes A_ROSTER_HASH = Bytes.wrap("A");
    private static final Bytes B_ROSTER_HASH = Bytes.wrap("B");
    private static final Roster C_ROSTER = new Roster(List.of(
            RosterEntry.newBuilder().nodeId(1L).build(),
            RosterEntry.newBuilder().nodeId(2L).build(),
            RosterEntry.newBuilder().nodeId(3L).build()));
    private static final Bytes C_ROSTER_HASH = Bytes.wrap("C");
    private static final TssConfig TSS_CONFIG = DEFAULT_CONFIG.getConfigData(TssConfig.class);
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);

    @Mock
    private AppContext appContext;

    @Mock
    private ActiveRosters activeRosters;

    @Mock
    private HintsLibrary library;

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private ConfigProviderImpl configProvider;

    @Mock
    private StoreMetricsServiceImpl storeMetricsService;

    @Mock
    private WritableStates writableStates;

    private State state;
    private WritableEntityIdStore writableEntityIdStore;

    private WritableHintsStoreImpl subject;

    @BeforeEach
    void setUp() {
        state = emptyState();
        writableEntityIdStore = new WritableEntityIdStoreImpl(new MapWritableStates(Map.of(
                ENTITY_ID_STATE_ID,
                new FunctionWritableSingletonState<>(
                        ENTITY_ID_STATE_ID,
                        ENTITY_ID_STATE_LABEL,
                        () -> EntityNumber.newBuilder().build(),
                        c -> {}),
                ENTITY_COUNTS_STATE_ID,
                new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        ENTITY_COUNTS_STATE_LABEL,
                        () -> EntityCounts.newBuilder().numNodes(2).build(),
                        c -> {}),
                HIGHEST_NODE_ID_STATE_ID,
                new FunctionWritableSingletonState<>(
                        HIGHEST_NODE_ID_STATE_ID,
                        HIGHEST_NODE_ID_STATE_LABEL,
                        () -> NodeId.newBuilder().id(1L).build(),
                        c -> {}))));
        subject = new WritableHintsStoreImpl(state.getWritableStates(HintsService.NAME), writableEntityIdStore);
    }

    @Test
    void refusesToGetOrCreateForHandoff() {
        given(activeRosters.phase()).willReturn(HANDOFF);

        assertNull(subject.getConstructionFor(activeRosters));
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, TSS_CONFIG));
    }

    @Test
    void findsMatchingTransitionConstructionInActiveConstructionIfThere() {
        given(activeRosters.phase()).willReturn(TRANSITION);
        given(activeRosters.sourceRosterHash()).willReturn(A_ROSTER_HASH);
        given(activeRosters.targetRosterHash()).willReturn(B_ROSTER_HASH);
        final var active = HintsConstruction.newBuilder()
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(B_ROSTER_HASH)
                .build();
        setConstructions(active, HintsConstruction.DEFAULT);

        assertSame(active, subject.getConstructionFor(activeRosters));
        assertSame(active, subject.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, TSS_CONFIG));
    }

    @Test
    void findsMatchingTransitionConstructionInNextConstructionIfThere() {
        given(activeRosters.phase()).willReturn(TRANSITION);
        given(activeRosters.sourceRosterHash()).willReturn(B_ROSTER_HASH);
        given(activeRosters.targetRosterHash()).willReturn(C_ROSTER_HASH);
        final var active = HintsConstruction.newBuilder()
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(B_ROSTER_HASH)
                .build();
        final var next = HintsConstruction.newBuilder()
                .sourceRosterHash(B_ROSTER_HASH)
                .targetRosterHash(C_ROSTER_HASH)
                .build();
        setConstructions(active, next);

        final var construction = subject.getConstructionFor(activeRosters);

        assertSame(next, construction);
    }

    @Test
    void createsBootstrapConstructionIfNotPresent() {
        given(activeRosters.phase()).willReturn(BOOTSTRAP);
        given(activeRosters.sourceRosterHash()).willReturn(A_ROSTER_HASH);
        given(activeRosters.targetRosterHash()).willReturn(A_ROSTER_HASH);

        final var construction = subject.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, TSS_CONFIG);

        assertEquals(1L, construction.constructionId());
        assertFalse(construction.hasGracePeriodEndTime());
        assertEquals(0, construction.crsId());
        assertEquals(A_ROSTER_HASH, construction.sourceRosterHash());
        assertEquals(A_ROSTER_HASH, construction.targetRosterHash());

        final var activeConstruction = state.getWritableStates(HintsService.NAME)
                .<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID)
                .get();
        requireNonNull(activeConstruction);
        assertSame(construction, activeConstruction);
    }

    @Test
    void setsAsNextConstructionAndRotatesKeysDuringTransition() {
        given(activeRosters.phase()).willReturn(TRANSITION);
        given(activeRosters.sourceRosterHash()).willReturn(B_ROSTER_HASH);
        given(activeRosters.targetRosterHash()).willReturn(C_ROSTER_HASH);
        final var active = HintsConstruction.newBuilder()
                .constructionId(2L)
                .hintsScheme(HintsScheme.DEFAULT)
                .crsId(1L)
                .numParties(4)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(B_ROSTER_HASH)
                .build();
        setConstructions(active, HintsConstruction.DEFAULT);
        assertSame(active, subject.getActiveConstruction());
        final var key = Bytes.wrap("ONE");
        final var nextKey = Bytes.wrap("TWO");
        final long rotatingKeyNodeId = 666L;
        final int numParties = HintsService.partySizeForRoster(C_ROSTER);
        subject.setCrsState(completedCrs(1, numParties));
        subject.setHintsKey(rotatingKeyNodeId, 0, numParties, 1, key, CONSENSUS_NOW.minusSeconds(1440));
        subject.setHintsKey(rotatingKeyNodeId, 0, numParties, 1, nextKey, CONSENSUS_NOW.minusSeconds(1439));
        final long newKeyNodeId = 42L;
        final var newKey = Bytes.wrap("THREE");
        assertTrue(subject.setHintsKey(newKeyNodeId, 1, numParties, 1, newKey, CONSENSUS_NOW.minusSeconds(1L)));

        var construction = subject.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, TSS_CONFIG);
        assertFalse(construction.hasGracePeriodEndTime());
        subject.bindConstructionToCrs(construction.constructionId(), 1, numParties);
        construction = subject.startHintsKeyGracePeriod(
                construction.constructionId(),
                CONSENSUS_NOW,
                CONSENSUS_NOW.plus(TSS_CONFIG.transitionHintsKeyGracePeriod()));

        assertEquals(3L, construction.constructionId());
        final var expectedGracePeriodEndTime =
                asTimestamp(CONSENSUS_NOW.plus(TSS_CONFIG.transitionHintsKeyGracePeriod()));
        assertEquals(expectedGracePeriodEndTime, construction.gracePeriodEndTimeOrThrow());
        assertEquals(B_ROSTER_HASH, construction.sourceRosterHash());
        assertEquals(C_ROSTER_HASH, construction.targetRosterHash());

        final var nextConstruction = state.getWritableStates(HintsService.NAME)
                .<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID)
                .get();
        requireNonNull(nextConstruction);
        assertSame(construction, nextConstruction);

        final var rotatedPartyId = new HintsPartyId(0, numParties, 1);
        final var updatedKeySet = state.getWritableStates(HintsService.NAME)
                .<HintsPartyId, HintsKeySet>get(V059HintsSchema.HINTS_KEY_SETS_STATE_ID)
                .get(rotatedPartyId);
        requireNonNull(updatedKeySet);
        assertEquals(666L, updatedKeySet.nodeId());
        assertEquals(nextKey, updatedKeySet.key());
        assertEquals(asTimestamp(CONSENSUS_NOW), updatedKeySet.adoptionTime());
        assertEquals(0, updatedKeySet.nextKey().length());

        final var newPartyId = new HintsPartyId(1, numParties, 1);
        final var newKeySet = state.getWritableStates(HintsService.NAME)
                .<HintsPartyId, HintsKeySet>get(V059HintsSchema.HINTS_KEY_SETS_STATE_ID)
                .get(newPartyId);
        requireNonNull(newKeySet);
        assertEquals(newKeyNodeId, newKeySet.nodeId());
        assertEquals(newKey, newKeySet.key());
        assertEquals(asTimestamp(CONSENSUS_NOW.minusSeconds(1L)), newKeySet.adoptionTime());
    }

    @Test
    void canSetPreprocessingStartTimeIfConstructionIdExists() {
        final var nextConstruction =
                HintsConstruction.newBuilder().constructionId(456L).build();
        setConstructions(HintsConstruction.newBuilder().constructionId(123L).build(), nextConstruction);
        assertSame(nextConstruction, subject.getNextConstruction());

        assertThrows(IllegalArgumentException.class, () -> subject.setPreprocessingStartTime(0L, CONSENSUS_NOW));
        subject.setPreprocessingStartTime(123L, CONSENSUS_NOW);
        assertEquals(
                asTimestamp(CONSENSUS_NOW),
                constructionNow(ACTIVE_HINTS_CONSTRUCTION_STATE_ID).preprocessingStartTimeOrThrow());
        assertFalse(constructionNow(NEXT_HINTS_CONSTRUCTION_STATE_ID).hasPreprocessingStartTime());

        subject.setPreprocessingStartTime(123L, CONSENSUS_NOW);
        assertEquals(
                asTimestamp(CONSENSUS_NOW),
                constructionNow(ACTIVE_HINTS_CONSTRUCTION_STATE_ID).preprocessingStartTimeOrThrow());

        final var then = CONSENSUS_NOW.plusSeconds(1L);
        subject.setPreprocessingStartTime(456L, then);
        assertEquals(
                asTimestamp(then),
                constructionNow(NEXT_HINTS_CONSTRUCTION_STATE_ID).preprocessingStartTimeOrThrow());
    }

    @Test
    void canSetHintsScheme() {
        setConstructions(
                HintsConstruction.newBuilder().constructionId(123L).build(),
                HintsConstruction.newBuilder().constructionId(456L).build());
        final var verificationKey = Bytes.wrap("VK");
        final var keys = new PreprocessedKeys(Bytes.wrap(new byte[49]), verificationKey);
        final var nodePartyIds = Map.of(1L, 2, 3L, 6);
        final var nodeWeights = Map.of(1L, 100L, 3L, 300L);
        assertNull(subject.getActiveVerificationKey());

        subject.setHintsScheme(456L, keys, nodePartyIds, nodeWeights);

        final var construction = constructionNow(NEXT_HINTS_CONSTRUCTION_STATE_ID);
        assertEquals(keys, construction.hintsSchemeOrThrow().preprocessedKeysOrThrow());
        assertEquals(
                List.of(new NodePartyId(1L, 2, 100L), new NodePartyId(3L, 6, 300L)),
                construction.hintsSchemeOrThrow().nodePartyIds());
        assertNull(subject.getActiveVerificationKey());

        subject.setHintsScheme(123L, keys, nodePartyIds, nodeWeights);
        assertEquals(verificationKey, subject.getActiveVerificationKey());
    }

    @Test
    void purgingStateAfterHandoffHasTrueExpectedEffectIfSomethingHappened() {
        final var activeConstruction = HintsConstruction.newBuilder()
                .constructionId(123L)
                .crsId(1L)
                .numParties(4)
                .hintsScheme(HintsScheme.DEFAULT)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(A_ROSTER_HASH)
                .build();
        final var nextConstruction = HintsConstruction.newBuilder()
                .constructionId(456L)
                .crsId(2L)
                .numParties(8)
                .targetRosterHash(C_ROSTER_HASH)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        setConstructions(activeConstruction, nextConstruction);
        subject.setCrsState(completedCrs(1, 4));
        subject.setNextCrsState(completedCrs(2, 8)
                .copyBuilder()
                .constructionId(456)
                .sourceRosterHash(nextConstruction.sourceRosterHash())
                .targetRosterHash(C_ROSTER_HASH)
                .build());
        final var prevRoster =
                new Roster(List.of(RosterEntry.newBuilder().nodeId(0L).build()));
        addSomeVotesFor(123L, prevRoster);
        addSomeHintsKeySetsFor(prevRoster);
        final var votesBefore = subject.getVotes(123L, Set.of(0L, 1L));
        assertEquals(1, votesBefore.size());
        assertEquals(DEFAULT_VOTE, votesBefore.get(0L));
        final var publicationsBefore = subject.getHintsKeyPublications(Set.of(0L), partySizeForRoster(A_ROSTER), 1);
        assertEquals(1, publicationsBefore.size());

        subject.handoff(prevRoster, C_ROSTER, C_ROSTER_HASH, false);

        assertSame(nextConstruction, constructionNow(ACTIVE_HINTS_CONSTRUCTION_STATE_ID));

        assertEquals(0L, votesNow().size());
        assertEquals(0L, keySetsNow().size());
    }

    @Test
    void rebindingCompletedTargetPreservesSchemeAndCrsProvenance() {
        final var active = HintsConstruction.newBuilder()
                .constructionId(123L)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(B_ROSTER_HASH)
                .crsId(1L)
                .numParties(4)
                .hintsScheme(HintsScheme.newBuilder()
                        .preprocessedKeys(PreprocessedKeys.DEFAULT)
                        .build())
                .build();
        final var next = HintsConstruction.newBuilder().constructionId(124L).build();
        setConstructions(active, next);
        subject.setCrsState(CRSState.newBuilder()
                .ceremonyId(1L)
                .numParties(4)
                .stage(CRSStage.COMPLETED)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(B_ROSTER_HASH)
                .crs(Bytes.wrap(new byte[304 + 288 * 4]))
                .build());
        final var originalCrs = subject.getCrsState();

        assertFalse(subject.rebindActiveTargetRosterHash(A_ROSTER_HASH, C_ROSTER_HASH));
        assertEquals(active, subject.getActiveConstruction());
        assertTrue(subject.rebindActiveTargetRosterHash(B_ROSTER_HASH, C_ROSTER_HASH));
        assertEquals(active.copyBuilder().targetRosterHash(C_ROSTER_HASH).build(), subject.getActiveConstruction());
        assertEquals(originalCrs, subject.getCrsState());
        assertEquals(next, subject.getNextConstruction());

        subject.setCrsState(originalCrs
                .copyBuilder()
                .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                .build());
        assertFalse(subject.rebindActiveTargetRosterHash(C_ROSTER_HASH, B_ROSTER_HASH));
        setConstructions(
                active.copyBuilder()
                        .gracePeriodEndTime(asTimestamp(CONSENSUS_NOW))
                        .build(),
                next);
        subject.setCrsState(originalCrs);
        assertFalse(subject.rebindActiveTargetRosterHash(B_ROSTER_HASH, C_ROSTER_HASH));
    }

    @Test
    void handoffPurgesUpcomingConstructionVotes() {
        // The upcoming construction's voters are the outgoing roster (its source roster == the
        // fromRoster passed to handoff); its votes must be purged so they are not orphaned once it
        // becomes active. The outgoing active construction here has no votes, so a purge to empty
        // can only come from the upcoming construction being purged.
        final var activeConstruction = HintsConstruction.newBuilder()
                .constructionId(123L)
                .crsId(1L)
                .numParties(4)
                .hintsScheme(HintsScheme.DEFAULT)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(A_ROSTER_HASH)
                .build();
        final var nextConstruction = HintsConstruction.newBuilder()
                .constructionId(456L)
                .crsId(2L)
                .numParties(8)
                .sourceRosterHash(C_ROSTER_HASH)
                .targetRosterHash(C_ROSTER_HASH)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        setConstructions(activeConstruction, nextConstruction);
        subject.setCrsState(completedCrs(1, 4));
        subject.setNextCrsState(completedCrs(2, 8)
                .copyBuilder()
                .constructionId(456)
                .sourceRosterHash(nextConstruction.sourceRosterHash())
                .targetRosterHash(C_ROSTER_HASH)
                .build());
        addSomeVotesFor(456L, C_ROSTER);
        assertEquals(3, subject.getVotes(456L, Set.of(1L, 2L, 3L)).size());

        subject.handoff(C_ROSTER, C_ROSTER, C_ROSTER_HASH, false);

        assertSame(nextConstruction, constructionNow(ACTIVE_HINTS_CONSTRUCTION_STATE_ID));
        assertEquals(0L, votesNow().size());
    }

    @Test
    void setCrsState() {
        final var crsState = setInitialCrsState();

        assertEquals(crsState, subject.getCrsState());
    }

    @Test
    void movesToNextNode() {
        setInitialCrsState();

        subject.moveToNextNode(1L, Instant.ofEpochSecond(1_234_567L));
        assertEquals(1L, subject.getCrsState().nextContributingNodeId());
        assertEquals(
                asTimestamp(Instant.ofEpochSecond(1_234_567L)),
                subject.getCrsState().contributionEndTime());
    }

    @Test
    void addsCrsPublications() {
        subject.addCrsPublication(0L, CrsPublicationTransactionBody.DEFAULT);
        assertEquals(1, subject.getCrsPublications().size());
        assertEquals(
                CrsPublicationTransactionBody.DEFAULT,
                subject.getCrsPublications().get(0));
    }

    @Test
    void ceremonyAllocationSurvivesDiscardAndStaleActiveSnapshots() {
        final var original = completedCrs(3, 4);
        subject.setCrsState(original);
        assertEquals(4, subject.allocateCrsId());
        subject.setNextCrsState(completedCrs(4, 8));
        subject.setNextCrsState(CRSState.DEFAULT);
        subject.setCrsState(original);
        assertEquals(4, subject.getCrsState().lastUsedCeremonyId());
        assertEquals(5, subject.allocateCrsId());
        assertEquals(original.crs(), subject.getCrsState().crs());
    }

    @Test
    void hintsKeysCannotCrossCrsGenerations() {
        final var oldKey = Bytes.wrap("old");
        final var newKey = Bytes.wrap("new");
        assertTrue(subject.setHintsKey(1, 0, 4, 7, oldKey, CONSENSUS_NOW));
        assertTrue(subject.setHintsKey(1, 0, 4, 8, newKey, CONSENSUS_NOW));
        assertEquals(
                oldKey,
                subject.getHintsKeyPublications(Set.of(1L), 4, 7).getFirst().hintsKey());
        assertEquals(
                newKey,
                subject.getHintsKeyPublications(Set.of(1L), 4, 8).getFirst().hintsKey());
        assertTrue(subject.getHintsKeyPublications(Set.of(1L), 4, 9).isEmpty());
    }

    @Test
    void keyGracePeriodRequiresTheExactCompletedCrs() {
        final var construction = HintsConstruction.newBuilder()
                .constructionId(2)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(B_ROSTER_HASH)
                .build();
        setConstructions(
                HintsConstruction.newBuilder()
                        .constructionId(1)
                        .hintsScheme(HintsScheme.DEFAULT)
                        .build(),
                construction);
        subject.bindConstructionToCrs(2, 9, 4);
        subject.setNextCrsState(completedCrs(9, 4)
                .copyBuilder()
                .constructionId(2)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(C_ROSTER_HASH)
                .build());
        assertEquals(CRSState.DEFAULT, subject.getCrsStateFor(subject.getNextConstruction()));
        assertThrows(IllegalStateException.class, () -> subject.startHintsKeyGracePeriod(2, CONSENSUS_NOW));
        assertFalse(subject.getNextConstruction().hasGracePeriodEndTime());
        subject.setNextCrsState(subject.getNextCrsState()
                .copyBuilder()
                .targetRosterHash(B_ROSTER_HASH)
                .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                .build());
        assertThrows(IllegalStateException.class, () -> subject.startHintsKeyGracePeriod(2, CONSENSUS_NOW));
        subject.setNextCrsState(subject.getNextCrsState()
                .copyBuilder()
                .stage(CRSStage.COMPLETED)
                .build());
        final var end = CONSENSUS_NOW.plusSeconds(30);
        assertEquals(
                asTimestamp(end),
                subject.startHintsKeyGracePeriod(2, CONSENSUS_NOW, end).gracePeriodEndTime());
    }

    @Test
    void completedHintsAloneDoNotMakeAConstructionReady() {
        setConstructions(
                HintsConstruction.DEFAULT,
                HintsConstruction.newBuilder()
                        .constructionId(2)
                        .targetRosterHash(B_ROSTER_HASH)
                        .hintsScheme(HintsScheme.DEFAULT)
                        .build());
        assertFalse(subject.isReadyToAdopt(B_ROSTER_HASH));
        assertFalse(subject.handoff(A_ROSTER, C_ROSTER, B_ROSTER_HASH, true));
    }

    @Test
    void forceHandoffCannotShrinkCapacityOrIgnoreTarget() {
        final var active = HintsConstruction.newBuilder()
                .constructionId(1)
                .crsId(1)
                .numParties(8)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        final var next = HintsConstruction.newBuilder()
                .constructionId(2)
                .crsId(2)
                .numParties(4)
                .targetRosterHash(B_ROSTER_HASH)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        setConstructions(active, next);
        subject.setCrsState(completedCrs(1, 8));
        subject.setNextCrsState(completedCrs(2, 4)
                .copyBuilder()
                .constructionId(2)
                .targetRosterHash(B_ROSTER_HASH)
                .build());
        assertFalse(subject.handoff(A_ROSTER, C_ROSTER, C_ROSTER_HASH, true));
        assertFalse(subject.handoff(A_ROSTER, C_ROSTER, B_ROSTER_HASH, true));
        assertSame(active, subject.getActiveConstruction());
        assertEquals(1, subject.getCrsState().ceremonyId());
        assertEquals(2, subject.getNextCrsState().ceremonyId());
    }

    @Test
    void promotesCrsAndSchemeTogetherAndPreservesAllocationHighWater() {
        final var active = HintsConstruction.newBuilder()
                .constructionId(1)
                .crsId(1)
                .numParties(4)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        final var next = HintsConstruction.newBuilder()
                .constructionId(2)
                .crsId(2)
                .numParties(8)
                .targetRosterHash(B_ROSTER_HASH)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        setConstructions(active, next);
        subject.setCrsState(
                completedCrs(1, 4).copyBuilder().lastUsedCeremonyId(10).build());
        subject.setNextCrsState(completedCrs(2, 8)
                .copyBuilder()
                .constructionId(2)
                .targetRosterHash(B_ROSTER_HASH)
                .build());
        assertTrue(subject.handoff(A_ROSTER, C_ROSTER, B_ROSTER_HASH, false));
        assertSame(next, subject.getActiveConstruction());
        assertEquals(2, subject.getCrsState().ceremonyId());
        assertEquals(10, subject.getCrsState().lastUsedCeremonyId());
        assertEquals(CRSState.DEFAULT, subject.getNextCrsState());
        assertEquals(HintsConstruction.DEFAULT, subject.getNextConstruction());
    }

    @Test
    void replacingAnIncompleteBootstrapKeepsWorkInTheActiveSlot() {
        final var previous = HintsConstruction.newBuilder()
                .constructionId(3)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(A_ROSTER_HASH)
                .build();
        setConstructions(previous, HintsConstruction.DEFAULT);
        given(activeRosters.phase()).willReturn(BOOTSTRAP);
        given(activeRosters.sourceRosterHash()).willReturn(B_ROSTER_HASH);
        given(activeRosters.targetRosterHash()).willReturn(B_ROSTER_HASH);
        final var replacement = subject.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, TSS_CONFIG);
        assertEquals(4, replacement.constructionId());
        assertSame(replacement, subject.getActiveConstruction());
        assertEquals(HintsConstruction.DEFAULT, subject.getNextConstruction());
        assertFalse(replacement.hasGracePeriodEndTime());
    }

    @Test
    void abandoningCandidatePreservesBothIdHighWaterMarks() {
        final var active = HintsConstruction.newBuilder()
                .constructionId(4)
                .crsId(2)
                .numParties(4)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        final var next = HintsConstruction.newBuilder()
                .constructionId(9)
                .crsId(3)
                .numParties(8)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(B_ROSTER_HASH)
                .build();
        setConstructions(active, next);
        subject.setCrsState(completedCrs(2, 4));
        subject.setNextCrsState(completedCrs(3, 8));
        subject.setHintsKey(0, 1, 4, 2, Bytes.wrap("active"), CONSENSUS_NOW);
        subject.setHintsKey(0, 1, 8, 3, Bytes.wrap("abandoned"), CONSENSUS_NOW);
        subject.addPreprocessingVote(0, 9, DEFAULT_VOTE);
        subject.abandonNextConstruction();
        assertEquals(HintsConstruction.DEFAULT, subject.getNextConstruction());
        assertEquals(CRSState.DEFAULT, subject.getNextCrsState());
        assertEquals(9, subject.getCrsState().lastUsedConstructionId());
        assertEquals(3, subject.getCrsState().lastUsedCeremonyId());
        assertTrue(subject.getVotes(9, Set.of(0L)).isEmpty());
        assertTrue(subject.getHintsKeyPublications(Set.of(0L), 8, 3).isEmpty());
        assertFalse(subject.getHintsKeyPublications(Set.of(0L), 4, 2).isEmpty());
        given(activeRosters.phase()).willReturn(TRANSITION);
        given(activeRosters.sourceRosterHash()).willReturn(A_ROSTER_HASH);
        given(activeRosters.targetRosterHash()).willReturn(B_ROSTER_HASH);
        assertEquals(
                10,
                subject.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, TSS_CONFIG)
                        .constructionId());
        assertEquals(4, subject.allocateCrsId());
    }

    @Test
    void reproposingTheSameRosterAndCrsNeverReusesTheAbandonedConstructionId() {
        final var active = HintsConstruction.newBuilder()
                .constructionId(4)
                .crsId(2)
                .numParties(4)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        final var abandoned = HintsConstruction.newBuilder()
                .constructionId(9)
                .crsId(2)
                .numParties(4)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(B_ROSTER_HASH)
                .build();
        setConstructions(active, abandoned);
        subject.setCrsState(completedCrs(2, 4));
        subject.setHintsKey(0, 1, 4, 2, Bytes.wrap("shared"), CONSENSUS_NOW);
        subject.abandonNextConstruction();
        given(activeRosters.phase()).willReturn(TRANSITION);
        given(activeRosters.sourceRosterHash()).willReturn(A_ROSTER_HASH);
        given(activeRosters.targetRosterHash()).willReturn(B_ROSTER_HASH);
        final var replacement = subject.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, TSS_CONFIG);
        final var bound = subject.bindConstructionToCrs(replacement.constructionId(), 2, 4);
        assertEquals(10, bound.constructionId());
        assertEquals(abandoned.crsId(), bound.crsId());
        assertFalse(subject.getHintsKeyPublications(Set.of(0L), 4, 2).isEmpty());
        subject.abandonNextConstruction();
        assertEquals(
                11,
                subject.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, TSS_CONFIG)
                        .constructionId());
    }

    @Test
    void reassignedPartyImmediatelyAdoptsTheNewOwnersKeyWithinTheSameCrs() {
        subject.setCrsState(completedCrs(2, 4));
        final var oldKey = Bytes.wrap("departed-node-key");
        final var queuedOldKey = Bytes.wrap("departed-node-queued-key");
        final var replacementKey = Bytes.wrap("replacement-node-key");
        assertTrue(subject.setHintsKey(0, 1, 4, 2, oldKey, CONSENSUS_NOW));
        assertFalse(subject.setHintsKey(0, 1, 4, 2, queuedOldKey, CONSENSUS_NOW.plusSeconds(1)));
        final var adoptionTime = CONSENSUS_NOW.plusSeconds(2);
        assertTrue(subject.setHintsKey(1, 1, 4, 2, replacementKey, adoptionTime));
        ((CommittableWritableStates) state.getWritableStates(HintsService.NAME)).commit();

        final var restarted =
                new ReadableHintsStoreImpl(state.getReadableStates(HintsService.NAME), writableEntityIdStore);
        final var publications = restarted.getHintsKeyPublications(Set.of(1L), 4, 2);
        assertEquals(1, publications.size());
        assertEquals(replacementKey, publications.getFirst().hintsKey());
        assertEquals(adoptionTime, publications.getFirst().adoptionTime());
        assertTrue(restarted.getHintsKeyPublications(Set.of(0L), 4, 2).isEmpty());
        assertEquals(Bytes.EMPTY, keySetsNow().get(new HintsPartyId(1, 4, 2)).nextKey());
        assertEquals(2, restarted.getCrsState().ceremonyId());
        assertEquals(completedCrs(2, 4).crs(), restarted.getCrsState().crs());
    }

    @Test
    void idleAbandonmentDoesNotRewriteAnySingleton() {
        subject.setCrsState(completedCrs(2, 4));
        final var states = state.getWritableStates(HintsService.NAME);
        ((CommittableWritableStates) states).commit();

        subject.abandonNextConstruction();
        subject.abandonNextConstruction();

        assertFalse(states.getSingleton(CRS_STATE_STATE_ID).isModified());
        assertFalse(states.getSingleton(NEXT_CRS_STATE_ID).isModified());
        assertFalse(states.getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID).isModified());
        assertFalse(states.getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID).isModified());
    }

    private CRSState setInitialCrsState() {
        final var crsState = CRSState.newBuilder()
                .crs(Bytes.wrap("test"))
                .nextContributingNodeId(0L)
                .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                .contributionEndTime(asTimestamp(Instant.ofEpochSecond(1_234_567L)))
                .build();
        final AtomicReference<CRSState> crsStateRef = new AtomicReference<>();
        given(writableStates.<CRSState>getSingleton(CRS_STATE_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        CRS_STATE_STATE_ID, CRS_STATE_STATE_LABEL, crsStateRef::get, crsStateRef::set));
        given(writableStates.<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        NEXT_HINTS_CONSTRUCTION_STATE_ID,
                        NEXT_HINTS_CONSTRUCTION_STATE_LABEL,
                        () -> HintsConstruction.DEFAULT,
                        c -> {}));
        given(writableStates.getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        ACTIVE_HINTS_CONSTRUCTION_STATE_ID,
                        ACTIVE_HINTS_CONSTRUCTION_STATE_LABEL,
                        () -> HintsConstruction.DEFAULT,
                        c -> {}));

        subject = new WritableHintsStoreImpl(writableStates, writableEntityIdStore);
        subject.setCrsState(crsState);
        return crsState;
    }

    private ReadableKVState<PreprocessingVoteId, PreprocessingVote> votesNow() {
        return state.getWritableStates(HintsService.NAME).get(V059HintsSchema.PREPROCESSING_VOTES_STATE_ID);
    }

    private ReadableKVState<HintsPartyId, HintsKeySet> keySetsNow() {
        return state.getWritableStates(HintsService.NAME).get(V059HintsSchema.HINTS_KEY_SETS_STATE_ID);
    }

    private HintsConstruction constructionNow(final int stateId) {
        final var construction = state.getWritableStates(HintsService.NAME)
                .<HintsConstruction>getSingleton(stateId)
                .get();
        return requireNonNull(construction);
    }

    private void setConstructions(@NonNull final HintsConstruction active, @NonNull final HintsConstruction next) {
        final var writableStates = state.getWritableStates(HintsService.NAME);
        state.getWritableStates(HintsService.NAME)
                .<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID)
                .put(active);
        state.getWritableStates(HintsService.NAME)
                .<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID)
                .put(next);
        ((CommittableWritableStates) writableStates).commit();
    }

    private void addSomeVotesFor(final long constructionId, @NonNull final Roster roster) {
        roster.rosterEntries()
                .forEach(entry -> subject.addPreprocessingVote(entry.nodeId(), constructionId, DEFAULT_VOTE));
    }

    private void addSomeHintsKeySetsFor(@NonNull final Roster roster) {
        final var writableStates = state.getWritableStates(HintsService.NAME);
        final var keySets = state.getWritableStates(HintsService.NAME)
                .<HintsPartyId, HintsKeySet>get(V059HintsSchema.HINTS_KEY_SETS_STATE_ID);
        final int numParties = partySizeForRoster(roster);
        for (int i = 0; i < numParties; i++) {
            final var partyId = new HintsPartyId(i, numParties, 1);
            final var keySet = HintsKeySet.newBuilder()
                    .nodeId(i)
                    .key(Bytes.wrap("KEY" + i))
                    .adoptionTime(asTimestamp(CONSENSUS_NOW.minusSeconds(i)))
                    .build();
            keySets.put(partyId, keySet);
        }
        ((CommittableWritableStates) writableStates).commit();
    }

    private void givenARosterLookup() {
        given(activeRosters.findRelatedRoster(A_ROSTER_HASH)).willReturn(A_ROSTER);
    }

    private void givenCRosterLookup() {
        given(activeRosters.findRelatedRoster(C_ROSTER_HASH)).willReturn(C_ROSTER);
    }

    private State emptyState() {
        return new FakeState()
                .addService(
                        HintsService.NAME,
                        Map.of(
                                ACTIVE_HINTS_CONSTRUCTION_STATE_ID,
                                new AtomicReference<>(HintsConstruction.DEFAULT),
                                NEXT_HINTS_CONSTRUCTION_STATE_ID,
                                new AtomicReference<>(HintsConstruction.DEFAULT),
                                CRS_STATE_STATE_ID,
                                new AtomicReference<>(CRSState.DEFAULT),
                                NEXT_CRS_STATE_ID,
                                new AtomicReference<>(CRSState.DEFAULT),
                                V059HintsSchema.HINTS_KEY_SETS_STATE_ID,
                                new java.util.HashMap<>(),
                                V059HintsSchema.PREPROCESSING_VOTES_STATE_ID,
                                new java.util.HashMap<>(),
                                com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_PUBLICATIONS_STATE_ID,
                                new java.util.HashMap<>()));
    }

    private static CRSState completedCrs(final long id, final int capacity) {
        return CRSState.newBuilder()
                .ceremonyId(id)
                .numParties(capacity)
                .stage(CRSStage.COMPLETED)
                .crs(Bytes.wrap("CRS" + id))
                .build();
    }
}
