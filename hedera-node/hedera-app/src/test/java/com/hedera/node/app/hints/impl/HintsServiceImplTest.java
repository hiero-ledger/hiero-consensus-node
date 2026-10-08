// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.HintsService.partySizeForRosterNodeCount;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.schemas.V059HintsSchema;
import com.hedera.node.app.hints.schemas.V060HintsSchema;
import com.hedera.node.app.hints.schemas.V073HintsSchema;
import com.hedera.node.app.hints.schemas.V079HintsSchema;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.TssMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsServiceImplTest {
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);

    @Mock
    private TssConfig tssConfig;

    @Mock
    private ActiveRosters activeRosters;

    @Mock
    private SchemaRegistry schemaRegistry;

    @Mock
    private WritableHintsStore hintsStore;

    @Mock
    private NodeInfo nodeInfo;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private HintsServiceComponent component;

    @Mock
    private HintsContext context;

    @Mock
    private HintsControllers controllers;

    @Mock
    private HintsController controller;

    @Mock
    private HintsLibrary library;

    @Mock
    private HintsSubmissions submissions;

    @Mock
    private HintsHandlers handlers;

    @Mock
    private HintsContext.Signing signing;

    @Mock
    private OnHintsFinished callback;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<HintsConstruction> activeConstructionState;

    @Mock
    private WritableSingletonState<HintsConstruction> nextConstructionState;

    @Mock
    private WritableSingletonState<CRSState> crsState;

    @Mock
    private WritableSingletonState<CRSState> nextCrsState;

    @Mock
    private RosterTransitionWeights transitionWeights;

    @Mock
    private WritableKVState<HintsPartyId, HintsKeySet> hintsKeys;

    @Mock
    private Configuration configuration;

    private HintsServiceImpl subject;

    @BeforeEach
    void setUp() {
        subject = new HintsServiceImpl(component, library);
        lenient()
                .when(writableStates.<CRSState>getSingleton(V079HintsSchema.NEXT_CRS_STATE_ID))
                .thenReturn(nextCrsState);
    }

    @Test
    void metadataAsExpected() {
        assertEquals(HintsService.NAME, subject.getServiceName());
        assertEquals(HintsService.MIGRATION_ORDER, subject.migrationOrder());
    }

    @Test
    void stopsControllersWorkWhenAsked() {
        given(component.controllers()).willReturn(controllers);

        subject.stop();

        verify(controllers).stop();
    }

    @Test
    void handoffIsNoop() {
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.HANDOFF);
        given(component.controllers()).willReturn(controllers);

        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, true);

        verify(controllers).stop();
        verify(hintsStore).abandonNextConstruction();
    }

    @Test
    void delegatesActiveConstruction() {
        given(context.activeConstruction()).willReturn(HintsConstruction.DEFAULT);
        given(component.signingContext()).willReturn(context);

        assertSame(HintsConstruction.DEFAULT, subject.activeConstruction());
    }

    @Test
    void forwardsFinishedConstructionCallbackWhenRegistered() {
        final var construction = HintsConstruction.DEFAULT;
        subject.onFinishedConstruction(callback);

        subject.accept(hintsStore, construction, context);

        verify(callback).accept(hintsStore, construction, context);
    }

    @Test
    void ignoresFinishedConstructionWhenNoCallbackRegistered() {
        subject.accept(hintsStore, HintsConstruction.DEFAULT, context);

        verifyNoInteractions(callback);
    }

    @Test
    void delegatesReadinessAndVerificationKeys() {
        given(component.signingContext()).willReturn(context);
        given(context.isReady()).willReturn(true);
        given(context.verificationKeyOrThrow()).willReturn(Bytes.EMPTY);

        assertTrue(subject.isReady());
        assertThat(subject.verificationKey()).isSameAs(Bytes.EMPTY);
        assertThat(subject.activeVerificationKeyOrThrow()).isSameAs(Bytes.EMPTY);
    }

    @Test
    void signThrowsIfContextNotReady() {
        given(component.signingContext()).willReturn(context);
        given(context.isReady()).willReturn(false);

        assertThrows(IllegalStateException.class, () -> subject.sign(Bytes.EMPTY));

        verify(component, never()).submissions();
    }

    @Test
    void signCreatesSigningSubmitsPartialSignatureAndRemovesFromMapOnCompletion() {
        final var blockHash = Bytes.wrap("block-hash".getBytes());
        final var signings = new ConcurrentHashMap<Bytes, BlockHashSigning>();
        given(component.signingContext()).willReturn(context);
        given(context.isReady()).willReturn(true);
        given(component.signings()).willReturn(signings);
        given(component.submissions()).willReturn(submissions);
        final var submissionFuture = CompletableFuture.<Void>completedFuture(null);
        given(signing.constructionId()).willReturn(123L);
        given(submissions.submitPartialSignature(123L, blockHash)).willReturn(submissionFuture);
        given(context.newSigningForActiveConstruction(eq(blockHash), any(Runnable.class)))
                .willReturn(signing);

        final var returned = subject.sign(blockHash);

        assertSame(signing, returned.signing());
        assertSame(submissionFuture, returned.submissionFuture());
        assertSame(signing, signings.get(blockHash));
        final var onCompletion = ArgumentCaptor.forClass(Runnable.class);
        verify(context).newSigningForActiveConstruction(eq(blockHash), onCompletion.capture());
        verify(submissions).submitPartialSignature(123L, blockHash);

        onCompletion.getValue().run();

        assertFalse(signings.containsKey(blockHash));
    }

    @Test
    void signReusesExistingSigningForSameBlockHash() {
        final var blockHash = Bytes.wrap("same-hash".getBytes());
        final var signings = new ConcurrentHashMap<Bytes, BlockHashSigning>();
        signings.put(blockHash, signing);
        given(component.signingContext()).willReturn(context);
        given(context.isReady()).willReturn(true);
        given(component.signings()).willReturn(signings);
        given(component.submissions()).willReturn(submissions);
        final var submissionFuture = CompletableFuture.<Void>completedFuture(null);
        given(signing.constructionId()).willReturn(123L);
        given(submissions.submitPartialSignature(123L, blockHash)).willReturn(submissionFuture);

        final var returned = subject.sign(blockHash);

        assertSame(signing, returned.signing());
        assertSame(submissionFuture, returned.submissionFuture());
        verify(context, never()).newSigningForActiveConstruction(any(), any(Runnable.class));
        verify(submissions).submitPartialSignature(123L, blockHash);
    }

    @Test
    void doesNothingAtBootstrapIfTheConstructionIsComplete() {
        given(component.controllers()).willReturn(controllers);
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.BOOTSTRAP);
        final var construction =
                HintsConstruction.newBuilder().hintsScheme(HintsScheme.DEFAULT).build();
        given(hintsStore.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, tssConfig))
                .willReturn(construction);

        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, true);

        verify(controllers).stop();
    }

    @Test
    void usesControllerIfTheConstructionIsIncompleteDuringTransition() {
        given(activeRosters.targetRoster()).willReturn(Roster.DEFAULT);
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.TRANSITION);
        final var construction = HintsConstruction.newBuilder()
                .crsId(1)
                .numParties(8)
                .gracePeriodEndTime(asTimestamp(CONSENSUS_NOW.plusSeconds(1)))
                .build();
        given(hintsStore.getCrsStateFor(construction))
                .willReturn(CRSState.newBuilder().stage(COMPLETED).build());
        given(hintsStore.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, tssConfig))
                .willReturn(construction);
        given(component.controllers()).willReturn(controllers);
        given(component.signingContext()).willReturn(context);
        given(context.activeConstruction()).willReturn(HintsConstruction.DEFAULT);
        given(controllers.getOrCreateFor(activeRosters, construction, hintsStore, HintsConstruction.DEFAULT))
                .willReturn(controller);

        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, true);

        verify(controller).advanceConstruction(CONSENSUS_NOW, hintsStore, true);
    }

    @Test
    void handoffDoesNothingWhenStoreReportsNoChange() {
        given(hintsStore.handoff(Roster.DEFAULT, Roster.DEFAULT, Bytes.EMPTY, false))
                .willReturn(false);

        assertFalse(subject.handoff(hintsStore, Roster.DEFAULT, Roster.DEFAULT, Bytes.EMPTY, false));

        verify(hintsStore).handoff(Roster.DEFAULT, Roster.DEFAULT, Bytes.EMPTY, false);
        verify(component, never()).signingContext();
    }

    @Test
    void handoffUpdatesSigningContextWhenStoreChanges() {
        final var construction = HintsConstruction.newBuilder()
                .constructionId(123L)
                .crsId(1)
                .numParties(8)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        given(hintsStore.handoff(Roster.DEFAULT, Roster.DEFAULT, Bytes.EMPTY, true))
                .willReturn(true);
        given(hintsStore.getActiveConstruction()).willReturn(construction);
        given(component.signingContext()).willReturn(context);

        assertTrue(subject.handoff(hintsStore, Roster.DEFAULT, Roster.DEFAULT, Bytes.EMPTY, true));

        verify(context).setConstruction(construction, null);
    }

    @Test
    void executeCrsWorkDoesNothingWithoutController() {
        given(component.controllers()).willReturn(controllers);
        given(controllers.getAnyInProgress()).willReturn(Optional.empty());

        subject.executeCrsWork(hintsStore, CONSENSUS_NOW, true, networkInfo);

        verifyNoInteractions(hintsStore, controller);
    }

    @Test
    void executeCrsWorkDelegatesToBoundController() {
        given(component.controllers()).willReturn(controllers);
        given(controllers.getAnyInProgress()).willReturn(Optional.of(controller));
        subject.executeCrsWork(hintsStore, CONSENSUS_NOW, false, networkInfo);
        verify(controller).advanceCrsWork(CONSENSUS_NOW, hintsStore, false);
        verifyNoInteractions(hintsStore, networkInfo, library);
    }

    @Test
    void doesGenesisSetupWithHintsEnabled() {
        final var newCrs = Bytes.wrap(new byte[] {4, 5, 6});
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        given(tssConfig.hintsEnabled()).willReturn(true);
        given(library.newCrs((short) partySizeForRosterNodeCount(7))).willReturn(newCrs);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(writableStates.<CRSState>getSingleton(V060HintsSchema.CRS_STATE_STATE_ID))
                .willReturn(crsState);

        assertTrue(subject.doGenesisSetup(writableStates, configuration, 7));

        verify(activeConstructionState).put(HintsConstruction.DEFAULT);
        verify(nextConstructionState).put(HintsConstruction.DEFAULT);
        verify(crsState)
                .put(CRSState.newBuilder()
                        .stage(com.hedera.hapi.node.state.hints.CRSStage.GATHERING_CONTRIBUTIONS)
                        .nextContributingNodeId(0L)
                        .crs(newCrs)
                        .initialCrs(newCrs)
                        .numParties(partySizeForRosterNodeCount(7))
                        .build());
    }

    @Test
    void doesGenesisSetupWithHintsDisabled() {
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        given(tssConfig.hintsEnabled()).willReturn(false);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(writableStates.<CRSState>getSingleton(V060HintsSchema.CRS_STATE_STATE_ID))
                .willReturn(crsState);

        assertTrue(subject.doGenesisSetup(writableStates, configuration, 4));

        verify(activeConstructionState).put(HintsConstruction.DEFAULT);
        verify(nextConstructionState).put(HintsConstruction.DEFAULT);
        verify(crsState).put(CRSState.DEFAULT);
    }

    @Test
    void doesGenesisSetupFromStartupNetworkTssMetadata() {
        final var activeConstruction = HintsConstruction.newBuilder()
                .constructionId(123L)
                .crsId(1)
                .numParties(8)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        final var startupCrsState = CRSState.newBuilder()
                .stage(COMPLETED)
                .ceremonyId(1)
                .numParties(8)
                .crs(Bytes.wrap(new byte[304 + 8 * 288]))
                .build();
        final var network = Network.newBuilder()
                .tssMetadata(TssMetadata.newBuilder()
                        .activeHintsConstruction(activeConstruction)
                        .crsState(startupCrsState))
                .build();
        subject = new HintsServiceImpl(component, library, () -> network);
        given(crsState.get()).willReturn(startupCrsState);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(writableStates.<CRSState>getSingleton(V060HintsSchema.CRS_STATE_STATE_ID))
                .willReturn(crsState);
        given(writableStates.<HintsPartyId, HintsKeySet>get(V059HintsSchema.HINTS_KEY_SETS_STATE_ID))
                .willReturn(hintsKeys);
        given(component.signingContext()).willReturn(context);

        assertTrue(subject.doGenesisSetup(writableStates, configuration, 7));

        verify(activeConstructionState).put(activeConstruction);
        verify(nextConstructionState).put(HintsConstruction.DEFAULT);
        verify(crsState).put(startupCrsState);
        verify(context).setConstruction(activeConstruction, startupCrsState);
    }

    @Test
    @SuppressWarnings("unchecked")
    void registersTwoSchemasWhenHintsEnabled() {
        given(component.signingContext()).willReturn(context);

        subject.registerSchemas(schemaRegistry);

        final var captor = ArgumentCaptor.forClass(Schema.class);
        verify(schemaRegistry, times(4)).register(captor.capture());
        final var schemas = captor.getAllValues();
        assertThat(schemas.getFirst()).isInstanceOf(V059HintsSchema.class);
        assertThat(schemas.get(1)).isInstanceOf(V060HintsSchema.class);
        assertThat(schemas.get(2)).isInstanceOf(V073HintsSchema.class);
        assertThat(schemas.getLast()).isInstanceOf(V079HintsSchema.class);
    }

    @Test
    void growingFromSixToSevenNodesStartsNextCeremonyBeforeHints() {
        final var selected = prepareTransition(7, 8);
        final var seed = Bytes.wrap("fresh seed");
        given(library.newCrs((short) 16)).willReturn(seed);
        given(hintsStore.allocateCrsId()).willReturn(2L);
        given(activeRosters.transitionWeights(any())).willReturn(transitionWeights);
        given(transitionWeights.sourceNodeWeights()).willReturn(new TreeMap<>(Map.of(42L, 2L, 99L, 1L)));
        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, true);
        assertEquals(16, selected.get().numParties());
        assertEquals(2, selected.get().ceremonyId());
        assertEquals(seed, selected.get().initialCrs());
        assertEquals(42, selected.get().nextContributingNodeIdOrThrow());
        verify(hintsStore, never()).setCrsState(any());
        verify(hintsStore, never()).startHintsKeyGracePeriod(anyLong(), any(), any());
        verify(controller, never()).advanceConstruction(any(), any(), any(Boolean.class));
    }

    @Test
    void shrinkingFromSevenToSixNodesKeepsSixteenParties() {
        prepareTransition(6, 16);
        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, true);
        verify(hintsStore).bindConstructionToCrs(2, 1, 16);
        verifyNoInteractions(library);
        verify(hintsStore).startHintsKeyGracePeriod(2, CONSENSUS_NOW, CONSENSUS_NOW.plusSeconds(300));
        verify(controller).advanceConstruction(CONSENSUS_NOW, hintsStore, true);
    }

    @Test
    void sameCapacityReusesCompletedCrs() {
        prepareTransition(5, 8);
        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, false);
        verify(hintsStore).bindConstructionToCrs(2, 1, 8);
        verifyNoInteractions(library);
        verify(controller).advanceConstruction(CONSENSUS_NOW, hintsStore, false);
    }

    @Test
    void unsupportedCapacityLeavesActiveSigningAndCrsUntouched() {
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.TRANSITION);
        given(activeRosters.targetRoster())
                .willReturn(new Roster(IntStream.range(0, 511)
                        .mapToObj(i ->
                                RosterEntry.newBuilder().nodeId(i).weight(1).build())
                        .toList()));
        given(hintsStore.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, tssConfig))
                .willReturn(HintsConstruction.newBuilder().constructionId(99).build());
        given(component.controllers()).willReturn(controllers);
        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, true);
        verify(controllers).stop();
        verify(controllers, never()).getOrCreateFor(any(), any(), any(), any());
        verify(hintsStore, never()).allocateCrsId();
        verify(hintsStore, never()).setCrsState(any());
        verify(hintsStore, never()).setNextCrsState(any());
        verifyNoInteractions(context, library);
    }

    private AtomicReference<CRSState> prepareTransition(int targetNodes, int activeParties) {
        final var old = HintsConstruction.newBuilder()
                .constructionId(1)
                .crsId(1)
                .numParties(activeParties)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        final var upcoming = HintsConstruction.newBuilder().constructionId(2).build();
        final var activeCrs = CRSState.newBuilder()
                .ceremonyId(1)
                .numParties(activeParties)
                .stage(COMPLETED)
                .crs(Bytes.wrap("active"))
                .build();
        final var selected = new AtomicReference<>(activeCrs);
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.TRANSITION);
        given(activeRosters.targetRoster())
                .willReturn(new Roster(IntStream.range(0, targetNodes)
                        .mapToObj(i ->
                                RosterEntry.newBuilder().nodeId(i).weight(1).build())
                        .toList()));
        given(hintsStore.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, tssConfig))
                .willReturn(upcoming);
        given(hintsStore.getCrsState()).willReturn(activeCrs);
        lenient().when(hintsStore.getActiveConstruction()).thenReturn(old);
        given(hintsStore.bindConstructionToCrs(eq(2L), anyLong(), anyInt())).willAnswer(inv -> upcoming.copyBuilder()
                .crsId((long) inv.getArgument(1))
                .numParties((int) inv.getArgument(2))
                .build());
        given(hintsStore.getCrsStateFor(any())).willAnswer(inv -> selected.get());
        lenient()
                .when(hintsStore.startHintsKeyGracePeriod(eq(2L), any(), any()))
                .thenAnswer(inv -> upcoming.copyBuilder()
                        .crsId(selected.get().ceremonyId())
                        .numParties(selected.get().numParties())
                        .gracePeriodEndTime(asTimestamp((Instant) inv.getArgument(2)))
                        .build());
        lenient()
                .doAnswer(inv -> {
                    final CRSState value = inv.getArgument(0);
                    if (!value.equals(CRSState.DEFAULT)) selected.set(value);
                    return null;
                })
                .when(hintsStore)
                .setNextCrsState(any());
        given(component.signingContext()).willReturn(context);
        given(context.activeConstruction()).willReturn(old);
        given(component.controllers()).willReturn(controllers);
        given(controllers.getOrCreateFor(any(), any(), any(), any())).willReturn(controller);
        lenient().when(tssConfig.crsUpdateContributionTime()).thenReturn(Duration.ofSeconds(10));
        lenient().when(tssConfig.transitionHintsKeyGracePeriod()).thenReturn(Duration.ofSeconds(300));
        return selected;
    }

    @Test
    void delegatesHandlersAndKeyToComponent() {
        given(component.handlers()).willReturn(handlers);
        given(component.signingContext()).willReturn(context);
        given(context.verificationKeyOrThrow()).willReturn(Bytes.EMPTY);

        assertThat(subject.handlers()).isSameAs(handlers);
        assertThat(subject.activeVerificationKeyOrThrow()).isSameAs(Bytes.EMPTY);
    }
}
