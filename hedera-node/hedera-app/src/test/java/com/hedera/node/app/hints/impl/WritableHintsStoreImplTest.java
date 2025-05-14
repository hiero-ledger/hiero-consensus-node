// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.hints.HintsService.partySizeForRoster;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINT_CONSTRUCTION_KEY;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINT_CONSTRUCTION_KEY;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.roster.ActiveRosters.Phase.BOOTSTRAP;
import static com.hedera.node.app.roster.ActiveRosters.Phase.HANDOFF;
import static com.hedera.node.app.roster.ActiveRosters.Phase.TRANSITION;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
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
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fixtures.state.FakeServiceMigrator;
import com.hedera.node.app.fixtures.state.FakeServicesRegistry;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.schemas.V059HintsSchema;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
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
    private WritableEntityCounters entityCounters;

    private WritableHintsStoreImpl subject;

    @BeforeEach
    void setUp() {
        state = emptyState();
        entityCounters = new WritableEntityIdStore(new MapWritableStates(Map.of(
                ENTITY_ID_STATE_KEY,
                new WritableSingletonStateBase<>(
                        ENTITY_ID_STATE_KEY, () -> EntityNumber.newBuilder().build(), c -> {}),
                ENTITY_COUNTS_KEY,
                new WritableSingletonStateBase<>(
                        ENTITY_COUNTS_KEY,
                        () -> EntityCounts.newBuilder().numNodes(2).build(),
                        c -> {}))));
        subject = new WritableHintsStoreImpl(state.getWritableStates(HintsService.NAME), entityCounters);
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
        givenARosterLookup();
        given(activeRosters.phase()).willReturn(BOOTSTRAP);
        given(activeRosters.sourceRosterHash()).willReturn(A_ROSTER_HASH);
        given(activeRosters.targetRosterHash()).willReturn(A_ROSTER_HASH);

        final var construction = subject.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, TSS_CONFIG);

        assertEquals(1L, construction.constructionId());
        final var expectedGracePeriodEndTime =
                asTimestamp(CONSENSUS_NOW.plus(TSS_CONFIG.bootstrapHintsKeyGracePeriod()));
        assertEquals(expectedGracePeriodEndTime, construction.gracePeriodEndTimeOrThrow());
        assertEquals(A_ROSTER_HASH, construction.sourceRosterHash());
        assertEquals(A_ROSTER_HASH, construction.targetRosterHash());

        final var activeConstruction = state.getWritableStates(HintsService.NAME)
                .<HintsConstruction>getSingleton(ACTIVE_HINT_CONSTRUCTION_KEY)
                .get();
        requireNonNull(activeConstruction);
        assertSame(construction, activeConstruction);
    }

    @Test
    void setsAsNextConstructionAndRotatesKeysDuringTransition() {
        givenCRosterLookup();
        given(activeRosters.phase()).willReturn(TRANSITION);
        given(activeRosters.sourceRosterHash()).willReturn(B_ROSTER_HASH);
        given(activeRosters.targetRosterHash()).willReturn(C_ROSTER_HASH);
        final var active = HintsConstruction.newBuilder()
                .constructionId(2L)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(B_ROSTER_HASH)
                .build();
        setConstructions(active, HintsConstruction.DEFAULT);
        assertSame(active, subject.getActiveConstruction());
        final var key = Bytes.wrap("ONE");
        final var nextKey = Bytes.wrap("TWO");
        final long rotatingKeyNodeId = 666L;
        final int numParties = HintsService.partySizeForRoster(C_ROSTER);
        subject.setHintsKey(rotatingKeyNodeId, 0, numParties, key, CONSENSUS_NOW.minusSeconds(1440));
        subject.setHintsKey(rotatingKeyNodeId, 0, numParties, nextKey, CONSENSUS_NOW.minusSeconds(1439));
        final long newKeyNodeId = 42L;
        final var newKey = Bytes.wrap("THREE");
        assertTrue(subject.setHintsKey(newKeyNodeId, 1, numParties, newKey, CONSENSUS_NOW.minusSeconds(1L)));

        final var construction = subject.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, TSS_CONFIG);

        assertEquals(3L, construction.constructionId());
        final var expectedGracePeriodEndTime =
                asTimestamp(CONSENSUS_NOW.plus(TSS_CONFIG.transitionHintsKeyGracePeriod()));
        assertEquals(expectedGracePeriodEndTime, construction.gracePeriodEndTimeOrThrow());
        assertEquals(B_ROSTER_HASH, construction.sourceRosterHash());
        assertEquals(C_ROSTER_HASH, construction.targetRosterHash());

        final var nextConstruction = state.getWritableStates(HintsService.NAME)
                .<HintsConstruction>getSingleton(NEXT_HINT_CONSTRUCTION_KEY)
                .get();
        requireNonNull(nextConstruction);
        assertSame(construction, nextConstruction);

        final var rotatedPartyId = new HintsPartyId(0, numParties);
        final var updatedKeySet = state.getWritableStates(HintsService.NAME)
                .<HintsPartyId, HintsKeySet>get(V059HintsSchema.HINTS_KEY_SETS_KEY)
                .get(rotatedPartyId);
        requireNonNull(updatedKeySet);
        assertEquals(666L, updatedKeySet.nodeId());
        assertEquals(nextKey, updatedKeySet.key());
        assertEquals(asTimestamp(CONSENSUS_NOW), updatedKeySet.adoptionTime());
        assertEquals(0, updatedKeySet.nextKey().length());

        final var newPartyId = new HintsPartyId(1, numParties);
        final var newKeySet = state.getWritableStates(HintsService.NAME)
                .<HintsPartyId, HintsKeySet>get(V059HintsSchema.HINTS_KEY_SETS_KEY)
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
                constructionNow(ACTIVE_HINT_CONSTRUCTION_KEY).preprocessingStartTimeOrThrow());
        assertFalse(constructionNow(NEXT_HINT_CONSTRUCTION_KEY).hasPreprocessingStartTime());

        subject.setPreprocessingStartTime(123L, CONSENSUS_NOW);
        assertEquals(
                asTimestamp(CONSENSUS_NOW),
                constructionNow(ACTIVE_HINT_CONSTRUCTION_KEY).preprocessingStartTimeOrThrow());

        final var then = CONSENSUS_NOW.plusSeconds(1L);
        subject.setPreprocessingStartTime(456L, then);
        assertEquals(
                asTimestamp(then), constructionNow(NEXT_HINT_CONSTRUCTION_KEY).preprocessingStartTimeOrThrow());
    }

    @Test
    void canSetHintsScheme() {
        setConstructions(
                HintsConstruction.newBuilder().constructionId(123L).build(),
                HintsConstruction.newBuilder().constructionId(456L).build());
        final var verificationKey = Bytes.wrap("VK");
        final var keys = new PreprocessedKeys(Bytes.EMPTY, verificationKey);
        final var nodePartyIds = Map.of(1L, 2, 3L, 6);
        assertNull(subject.getActiveVerificationKey());

        subject.setHintsScheme(456L, keys, nodePartyIds);

        final var construction = constructionNow(NEXT_HINT_CONSTRUCTION_KEY);
        assertEquals(keys, construction.hintsSchemeOrThrow().preprocessedKeysOrThrow());
        assertEquals(
                List.of(new NodePartyId(1L, 2), new NodePartyId(3L, 6)),
                construction.hintsSchemeOrThrow().nodePartyIds());
        assertNull(subject.getActiveVerificationKey());

        subject.setHintsScheme(123L, keys, nodePartyIds);
        assertEquals(verificationKey, subject.getActiveVerificationKey());
    }

    @Test
    void purgingStateAfterHandoffHasTrueExpectedEffectIfSomethingHappened() {
        final var activeConstruction = HintsConstruction.newBuilder()
                .constructionId(123L)
                .sourceRosterHash(A_ROSTER_HASH)
                .targetRosterHash(A_ROSTER_HASH)
                .build();
        final var nextConstruction = HintsConstruction.newBuilder()
                .constructionId(456L)
                .targetRosterHash(C_ROSTER_HASH)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        setConstructions(activeConstruction, nextConstruction);
        final var prevRoster =
                new Roster(List.of(RosterEntry.newBuilder().nodeId(0L).build()));
        addSomeVotesFor(123L, prevRoster);
        addSomeHintsKeySetsFor(prevRoster);
        final var votesBefore = subject.getVotes(123L, Set.of(0L, 1L));
        assertEquals(1, votesBefore.size());
        assertEquals(DEFAULT_VOTE, votesBefore.get(0L));
        final var publicationsBefore = subject.getHintsKeyPublications(Set.of(0L), partySizeForRoster(A_ROSTER));
        assertEquals(1, publicationsBefore.size());

        subject.handoff(prevRoster, C_ROSTER, C_ROSTER_HASH, false);

        assertSame(nextConstruction, constructionNow(ACTIVE_HINT_CONSTRUCTION_KEY));

        assertEquals(0L, votesNow().size());
        assertEquals(0L, keySetsNow().size());
    }

    @Test
    void setCrsState() {
        final var crsState = setInitialCrsState();

        assertEquals(crsState, subject.getCrsState());
    }

    @Test
    void movesToNextNode() {
        setInitialCrsState();

        subject.moveToNextNode(OptionalLong.of(1L), Instant.ofEpochSecond(1_234_567L));
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

    private CRSState setInitialCrsState() {
        final var crsState = CRSState.newBuilder()
                .crs(Bytes.wrap("test"))
                .nextContributingNodeId(0L)
                .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                .contributionEndTime(asTimestamp(Instant.ofEpochSecond(1_234_567L)))
                .build();
        final AtomicReference<CRSState> crsStateRef = new AtomicReference<>();
        given(writableStates.<CRSState>getSingleton(CRS_STATE_KEY))
                .willReturn(new WritableSingletonStateBase<>(CRS_STATE_KEY, crsStateRef::get, crsStateRef::set));
        given(writableStates.<HintsConstruction>getSingleton(NEXT_HINT_CONSTRUCTION_KEY))
                .willReturn(new WritableSingletonStateBase<>(
                        NEXT_HINT_CONSTRUCTION_KEY, () -> HintsConstruction.DEFAULT, c -> {}));
        given(writableStates.getSingleton(ACTIVE_HINT_CONSTRUCTION_KEY))
                .willReturn(new WritableSingletonStateBase<>(
                        ACTIVE_HINT_CONSTRUCTION_KEY, () -> HintsConstruction.DEFAULT, c -> {}));

        subject = new WritableHintsStoreImpl(writableStates, entityCounters);
        subject.setCrsState(crsState);
        return crsState;
    }

    private ReadableKVState<PreprocessingVoteId, PreprocessingVote> votesNow() {
        return state.getWritableStates(HintsService.NAME).get(V059HintsSchema.PREPROCESSING_VOTES_KEY);
    }

    private ReadableKVState<HintsPartyId, HintsKeySet> keySetsNow() {
        return state.getWritableStates(HintsService.NAME).get(V059HintsSchema.HINTS_KEY_SETS_KEY);
    }

    private HintsConstruction constructionNow(@NonNull final String key) {
        final var construction = state.getWritableStates(HintsService.NAME)
                .<HintsConstruction>getSingleton(key)
                .get();
        return requireNonNull(construction);
    }

    private void setConstructions(@NonNull final HintsConstruction active, @NonNull final HintsConstruction next) {
        final var writableStates = state.getWritableStates(HintsService.NAME);
        state.getWritableStates(HintsService.NAME)
                .<HintsConstruction>getSingleton(ACTIVE_HINT_CONSTRUCTION_KEY)
                .put(active);
        state.getWritableStates(HintsService.NAME)
                .<HintsConstruction>getSingleton(V059HintsSchema.NEXT_HINT_CONSTRUCTION_KEY)
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
                .<HintsPartyId, HintsKeySet>get(V059HintsSchema.HINTS_KEY_SETS_KEY);
        final int numParties = partySizeForRoster(roster);
        for (int i = 0; i < numParties; i++) {
            final var partyId = new HintsPartyId(i, numParties);
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
        final var state = new FakeState();
        final var servicesRegistry = new FakeServicesRegistry();
        Set.of(
                        new EntityIdService(),
                        new HintsServiceImpl(
                                NO_OP_METRICS,
                                ForkJoinPool.commonPool(),
                                appContext,
                                library,
                                DEFAULT_CONFIG
                                        .getConfigData(BlockStreamConfig.class)
                                        .blockPeriod()))
                .forEach(servicesRegistry::register);
        final var migrator = new FakeServiceMigrator();
        final var bootstrapConfig = new BootstrapConfigProviderImpl().getConfiguration();
        migrator.doMigrations(
                state,
                servicesRegistry,
                null,
                bootstrapConfig.getConfigData(VersionConfig.class).servicesVersion(),
                new ConfigProviderImpl().getConfiguration(),
                DEFAULT_CONFIG,
                NO_OP_METRICS,
                startupNetworks,
                storeMetricsService,
                configProvider,
                TEST_PLATFORM_STATE_FACADE);
        return state;
    }
}
