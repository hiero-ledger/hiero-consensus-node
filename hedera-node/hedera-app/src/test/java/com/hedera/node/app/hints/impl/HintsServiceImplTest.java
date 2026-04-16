// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.hints.HintsService.partySizeForRosterNodeCount;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.schemas.V059HintsSchema;
import com.hedera.node.app.hints.schemas.V060HintsSchema;
import com.hedera.node.app.hints.schemas.V073HintsSchema;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private Configuration configuration;

    private HintsServiceImpl subject;

    @BeforeEach
    void setUp() {
        subject = new HintsServiceImpl(component, library);
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

        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, true);

        verifyNoInteractions(hintsStore);
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
        final var signings = new ConcurrentHashMap<Bytes, HintsContext.Signing>();
        given(component.signingContext()).willReturn(context);
        given(context.isReady()).willReturn(true);
        given(component.signings()).willReturn(signings);
        given(component.submissions()).willReturn(submissions);
        given(submissions.submitPartialSignature(blockHash)).willReturn(CompletableFuture.completedFuture(null));
        given(context.newSigning(eq(blockHash), any(Runnable.class))).willReturn(signing);

        final var returned = subject.sign(blockHash);

        assertSame(signing, returned);
        assertSame(signing, signings.get(blockHash));
        final var onCompletion = ArgumentCaptor.forClass(Runnable.class);
        verify(context).newSigning(eq(blockHash), onCompletion.capture());
        verify(submissions).submitPartialSignature(blockHash);

        onCompletion.getValue().run();

        assertFalse(signings.containsKey(blockHash));
    }

    @Test
    void signReusesExistingSigningForSameBlockHash() {
        final var blockHash = Bytes.wrap("same-hash".getBytes());
        final var signings = new ConcurrentHashMap<Bytes, HintsContext.Signing>();
        signings.put(blockHash, signing);
        given(component.signingContext()).willReturn(context);
        given(context.isReady()).willReturn(true);
        given(component.signings()).willReturn(signings);
        given(component.submissions()).willReturn(submissions);
        given(submissions.submitPartialSignature(blockHash)).willReturn(CompletableFuture.completedFuture(null));

        final var returned = subject.sign(blockHash);

        assertSame(signing, returned);
        verify(context, never()).newSigning(any(), any(Runnable.class));
        verify(submissions).submitPartialSignature(blockHash);
    }

    @Test
    void doesNothingAtBootstrapIfTheConstructionIsComplete() {
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.BOOTSTRAP);
        final var construction =
                HintsConstruction.newBuilder().hintsScheme(HintsScheme.DEFAULT).build();
        given(hintsStore.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, tssConfig))
                .willReturn(construction);

        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, true);

        verifyNoInteractions(component);
    }

    @Test
    void usesControllerIfTheConstructionIsIncompleteDuringTransition() {
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.TRANSITION);
        final var construction = HintsConstruction.DEFAULT;
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

        subject.handoff(hintsStore, Roster.DEFAULT, Roster.DEFAULT, Bytes.EMPTY, false);

        verify(hintsStore).handoff(Roster.DEFAULT, Roster.DEFAULT, Bytes.EMPTY, false);
        verify(component, never()).signingContext();
    }

    @Test
    void handoffUpdatesSigningContextWhenStoreChanges() {
        final var construction = HintsConstruction.newBuilder()
                .constructionId(123L)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        given(hintsStore.handoff(Roster.DEFAULT, Roster.DEFAULT, Bytes.EMPTY, true))
                .willReturn(true);
        given(hintsStore.getActiveConstruction()).willReturn(construction);
        given(component.signingContext()).willReturn(context);

        subject.handoff(hintsStore, Roster.DEFAULT, Roster.DEFAULT, Bytes.EMPTY, true);

        verify(context).setConstruction(construction);
    }

    @Test
    void executeCrsWorkDoesNothingWithoutController() {
        given(component.controllers()).willReturn(controllers);
        given(controllers.getAnyInProgress()).willReturn(Optional.empty());

        subject.executeCrsWork(hintsStore, CONSENSUS_NOW, true, networkInfo);

        verifyNoInteractions(hintsStore, controller);
    }

    @Test
    void executeCrsWorkInitializesDefaultStateAndAdvancesWork() {
        final var newCrs = Bytes.wrap(new byte[] {1, 2, 3});
        given(component.controllers()).willReturn(controllers);
        given(controllers.getAnyInProgress()).willReturn(Optional.of(controller));
        given(hintsStore.getCrsState()).willReturn(CRSState.DEFAULT);
        given(networkInfo.addressBook()).willReturn(List.of(nodeInfo, nodeInfo, nodeInfo, nodeInfo, nodeInfo));
        given(library.newCrs((short) partySizeForRosterNodeCount(5))).willReturn(newCrs);

        subject.executeCrsWork(hintsStore, CONSENSUS_NOW, false, networkInfo);

        verify(hintsStore)
                .setCrsState(CRSState.newBuilder()
                        .stage(com.hedera.hapi.node.state.hints.CRSStage.GATHERING_CONTRIBUTIONS)
                        .nextContributingNodeId(0L)
                        .crs(newCrs)
                        .build());
        verify(controller).advanceCrsWork(CONSENSUS_NOW, hintsStore, false);
    }

    @Test
    void executeCrsWorkSkipsAdvanceWhenStateAlreadyCompleted() {
        final var completedState = CRSState.newBuilder()
                .stage(com.hedera.hapi.node.state.hints.CRSStage.COMPLETED)
                .build();
        given(component.controllers()).willReturn(controllers);
        given(controllers.getAnyInProgress()).willReturn(Optional.of(controller));
        given(hintsStore.getCrsState()).willReturn(completedState);

        subject.executeCrsWork(hintsStore, CONSENSUS_NOW, true, networkInfo);

        verify(controller, never()).advanceCrsWork(any(), any(), any(Boolean.class));
        verify(hintsStore, never()).setCrsState(any());
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
    @SuppressWarnings("unchecked")
    void registersTwoSchemasWhenHintsEnabled() {
        given(component.signingContext()).willReturn(context);

        subject.registerSchemas(schemaRegistry);

        final var captor = ArgumentCaptor.forClass(Schema.class);
        verify(schemaRegistry, times(3)).register(captor.capture());
        final var schemas = captor.getAllValues();
        assertThat(schemas.getFirst()).isInstanceOf(V059HintsSchema.class);
        assertThat(schemas.get(1)).isInstanceOf(V060HintsSchema.class);
        assertThat(schemas.getLast()).isInstanceOf(V073HintsSchema.class);
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
