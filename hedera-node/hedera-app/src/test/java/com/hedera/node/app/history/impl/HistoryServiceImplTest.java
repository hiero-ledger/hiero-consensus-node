// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.BOOTSTRAP;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.HANDOFF;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.TRANSITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.ProofKeySet;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.hints.KeyProvenanceSigner;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.history.schemas.V071HistorySchema;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeTssMetadata;
import com.hedera.node.internal.network.TssMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.time.Instant;
import java.util.concurrent.ForkJoinPool;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoryServiceImplTest {
    private static final Bytes CURRENT_VK = Bytes.wrap("Z");
    private static final Metrics NO_OP_METRICS = new NoOpMetrics();
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final TssConfig DEFAULT_TSS_CONFIG = DEFAULT_CONFIG.getConfigData(TssConfig.class);

    @Mock
    private AppContext appContext;

    @Mock
    private HistoryServiceComponent component;

    @Mock
    private ProofControllers controllers;

    @Mock
    private ProofController controller;

    @Mock
    private ActiveRosters activeRosters;

    @Mock
    private HistoryLibrary library;

    @Mock
    private HistoryHandlers handlers;

    @Mock
    private WritableHistoryStore store;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<ProtoBytes> ledgerIdState;

    @Mock
    private WritableSingletonState<HistoryProofConstruction> activeConstructionState;

    @Mock
    private WritableSingletonState<HistoryProofConstruction> nextConstructionState;

    @Mock
    private WritableKVState<NodeId, ProofKeySet> proofKeys;

    @Mock
    private KeyProvenanceSigner sourceSigner;

    private HistoryServiceImpl subject;

    @Test
    void metadataAsExpected() {
        withLiveSubject();
        assertEquals(HistoryService.NAME, subject.getServiceName());
        assertEquals(HistoryService.MIGRATION_ORDER, subject.migrationOrder());
    }

    @Test
    void notReadyUntilHistoryProofSetWithWrapsChainOfTrust() {
        withLiveSubject();
        assertFalse(subject.isReady());
        subject.setLatestHistoryProof(HistoryProof.DEFAULT);
        assertFalse(subject.isReady());
        subject.setLatestHistoryProof(HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.DEFAULT)
                .build());
        assertFalse(subject.isReady());
        subject.setLatestHistoryProof(HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("PROOF")))
                .build());
        assertTrue(subject.isReady());
    }

    @Test
    void refusesToProveMismatchedMetadata() {
        withLiveSubject();
        final var oldVk = Bytes.wrap("X");
        final var cotProof =
                ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("RAIN")).build();
        final var currentProof = HistoryProof.newBuilder()
                .targetHistory(History.newBuilder().metadata(CURRENT_VK))
                .chainOfTrustProof(cotProof)
                .build();

        subject.setLatestHistoryProof(currentProof);
        assertThrows(IllegalArgumentException.class, () -> subject.getCurrentChainOfTrustProof(oldVk));
        assertEquals(cotProof, subject.getCurrentChainOfTrustProof(CURRENT_VK));
    }

    @Test
    void usesComponentForHandlers() {
        withMockSubject();
        given(component.handlers()).willReturn(handlers);
        assertSame(handlers, subject.handlers());
    }

    @Test
    void stopsControllersWorkWhenAsked() {
        withMockSubject();
        given(component.controllers()).willReturn(controllers);

        subject.stop();

        verify(controllers).stop();
    }

    @Test
    void handoffIsNoop() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(HANDOFF);
        subject.reconcile(activeRosters, Bytes.EMPTY, store, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, true, null, false);
    }

    @Test
    void noopReconciliationIfBootstrapHasProof() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(BOOTSTRAP);
        final var wrapsExtensibleProof = HistoryProof.newBuilder()
                .uncompressedWrapsProof(Bytes.wrap("uncompressed"))
                .chainOfTrustProof(ChainOfTrustProof.DEFAULT)
                .build();
        given(store.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, false, null))
                .willReturn(HistoryProofConstruction.newBuilder()
                        .targetProof(wrapsExtensibleProof)
                        .build());

        subject.reconcile(activeRosters, null, store, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, true, null, false);

        verifyNoMoreInteractions(component);
    }

    @Test
    void activeReconciliationIfTransitionHasNoProofYet() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(TRANSITION);
        given(store.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, false, CURRENT_VK))
                .willReturn(HistoryProofConstruction.DEFAULT);
        given(store.getActiveConstruction()).willReturn(HistoryProofConstruction.DEFAULT);
        given(component.controllers()).willReturn(controllers);
        given(controllers.getOrCreateFor(
                        activeRosters,
                        HistoryProofConstruction.DEFAULT,
                        store,
                        HintsConstruction.DEFAULT,
                        HistoryProofConstruction.DEFAULT,
                        DEFAULT_CONFIG.getConfigData(TssConfig.class),
                        null))
                .willReturn(controller);

        subject.reconcile(
                activeRosters,
                CURRENT_VK,
                store,
                CONSENSUS_NOW,
                DEFAULT_TSS_CONFIG,
                true,
                HintsConstruction.DEFAULT,
                false);

        verify(controller).advanceConstruction(CONSENSUS_NOW, CURRENT_VK, store, true, DEFAULT_TSS_CONFIG);
    }

    @Test
    void bindsSourceAndTargetGenerationsBeforeCreatingControllerFromPersistedBinding() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(TRANSITION);
        final var sourceVk = Bytes.wrap("previous verification key");
        final var source = completedHints(7, sourceVk);
        final var initial = HistoryProofConstruction.newBuilder()
                .constructionId(42)
                .targetMetadata(CURRENT_VK)
                .build();
        final var expected = HintsKeyProvenance.newBuilder()
                .sourceHintsConstructionId(7)
                .targetHintsConstructionId(9)
                .sourceVerificationKey(sourceVk)
                .targetVerificationKey(CURRENT_VK)
                .build();
        final var bound = expected.copyBuilder().historyConstructionId(42).build();
        final var persisted = initial.copyBuilder().keyProvenance(bound).build();
        given(sourceSigner.constructionId()).willReturn(7L);
        given(sourceSigner.verificationKey()).willReturn(sourceVk);
        given(store.getOrCreateConstruction(
                        activeRosters, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, false, CURRENT_VK, expected))
                .willReturn(initial);
        given(store.bindKeyProvenance(42, bound)).willReturn(persisted);
        given(store.getActiveConstruction()).willReturn(HistoryProofConstruction.DEFAULT);
        given(component.controllers()).willReturn(controllers);
        given(controllers.getOrCreateFor(
                        activeRosters,
                        persisted,
                        store,
                        source,
                        HistoryProofConstruction.DEFAULT,
                        DEFAULT_TSS_CONFIG,
                        sourceSigner))
                .willReturn(controller);

        subject.reconcile(
                activeRosters,
                CURRENT_VK,
                store,
                CONSENSUS_NOW,
                DEFAULT_TSS_CONFIG,
                true,
                source,
                false,
                sourceSigner,
                9);

        final var order = inOrder(store, controllers, controller);
        order.verify(store).bindKeyProvenance(42, bound);
        order.verify(controllers)
                .getOrCreateFor(
                        activeRosters,
                        persisted,
                        store,
                        source,
                        HistoryProofConstruction.DEFAULT,
                        DEFAULT_TSS_CONFIG,
                        sourceSigner);
        order.verify(controller).advanceConstruction(CONSENSUS_NOW, CURRENT_VK, store, true, DEFAULT_TSS_CONFIG);
        verify(store, never()).getConstructionOrThrow(anyLong());
    }

    @Test
    void completedProvenanceBindingIsRetainedWithoutDirtyingStateAgain() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(TRANSITION);
        final var sourceVk = Bytes.wrap("previous verification key");
        final var source = completedHints(7, sourceVk);
        final var expected = HintsKeyProvenance.newBuilder()
                .sourceHintsConstructionId(7)
                .targetHintsConstructionId(9)
                .sourceVerificationKey(sourceVk)
                .targetVerificationKey(CURRENT_VK)
                .build();
        final var completedBinding = expected.copyBuilder()
                .historyConstructionId(42)
                .signature(Bytes.wrap("previous-key authorization"))
                .build();
        final var completed = HistoryProofConstruction.newBuilder()
                .constructionId(42)
                .targetMetadata(CURRENT_VK)
                .keyProvenance(completedBinding)
                .targetProof(HistoryProof.newBuilder().uncompressedWrapsProof(Bytes.wrap("WRAPS proof")))
                .build();
        given(sourceSigner.constructionId()).willReturn(7L);
        given(sourceSigner.verificationKey()).willReturn(sourceVk);
        given(store.getOrCreateConstruction(
                        activeRosters, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, false, CURRENT_VK, expected))
                .willReturn(completed);

        subject.reconcile(
                activeRosters,
                CURRENT_VK,
                store,
                CONSENSUS_NOW,
                DEFAULT_TSS_CONFIG,
                true,
                source,
                false,
                sourceSigner,
                9);

        verify(store, never()).bindKeyProvenance(anyLong(), any());
        verify(store, never()).getConstructionOrThrow(anyLong());
        verifyNoInteractions(component, controllers, controller);
        assertEquals(completedBinding, completed.keyProvenance());
    }

    @Test
    void initialGenesisMayCollectBeforeItsFirstHintsSchemeIsComplete() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(BOOTSTRAP);
        final var source = HintsConstruction.newBuilder().constructionId(7).build();
        given(store.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, false, null))
                .willReturn(HistoryProofConstruction.DEFAULT);
        given(store.getActiveConstruction()).willReturn(HistoryProofConstruction.DEFAULT);
        given(component.controllers()).willReturn(controllers);
        given(controllers.getOrCreateFor(
                        activeRosters,
                        HistoryProofConstruction.DEFAULT,
                        store,
                        source,
                        HistoryProofConstruction.DEFAULT,
                        DEFAULT_TSS_CONFIG,
                        null))
                .willReturn(controller);

        subject.reconcile(activeRosters, null, store, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, true, source, false, null, 7);

        verify(store, never()).bindKeyProvenance(anyLong(), any());
        verify(controller).advanceConstruction(CONSENSUS_NOW, null, store, true, DEFAULT_TSS_CONFIG);
    }

    @Test
    void freshGenesisUsesSameHintsGenerationWithoutPreviousEpochBinding() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(TRANSITION);
        final var source = completedHints(7, CURRENT_VK);
        given(sourceSigner.constructionId()).willReturn(7L);
        given(sourceSigner.verificationKey()).willReturn(CURRENT_VK);
        given(store.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, true, CURRENT_VK))
                .willReturn(HistoryProofConstruction.DEFAULT);
        given(store.getActiveConstruction()).willReturn(HistoryProofConstruction.DEFAULT);
        given(component.controllers()).willReturn(controllers);
        given(controllers.getOrCreateFor(
                        activeRosters,
                        HistoryProofConstruction.DEFAULT,
                        store,
                        source,
                        HistoryProofConstruction.DEFAULT,
                        DEFAULT_TSS_CONFIG,
                        sourceSigner))
                .willReturn(controller);

        subject.reconcile(
                activeRosters,
                CURRENT_VK,
                store,
                CONSENSUS_NOW,
                DEFAULT_TSS_CONFIG,
                true,
                source,
                true,
                sourceSigner,
                7);

        verify(store, never()).bindKeyProvenance(anyLong(), any());
        verify(controller).advanceConstruction(CONSENSUS_NOW, CURRENT_VK, store, true, DEFAULT_TSS_CONFIG);
    }

    @Test
    void rejectsMissingAndMismatchedSourceBeforeMutatingHistoryState() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(TRANSITION);
        final var source = completedHints(7, CURRENT_VK);
        final var incomplete = HintsConstruction.newBuilder().constructionId(7).build();

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.reconcile(
                        activeRosters,
                        CURRENT_VK,
                        store,
                        CONSENSUS_NOW,
                        DEFAULT_TSS_CONFIG,
                        true,
                        null,
                        false,
                        null,
                        9));
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.reconcile(
                        activeRosters,
                        CURRENT_VK,
                        store,
                        CONSENSUS_NOW,
                        DEFAULT_TSS_CONFIG,
                        true,
                        incomplete,
                        false,
                        null,
                        9));
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.reconcile(
                        activeRosters,
                        CURRENT_VK,
                        store,
                        CONSENSUS_NOW,
                        DEFAULT_TSS_CONFIG,
                        true,
                        source,
                        false,
                        null,
                        9));
        given(sourceSigner.constructionId()).willReturn(8L);
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.reconcile(
                        activeRosters,
                        CURRENT_VK,
                        store,
                        CONSENSUS_NOW,
                        DEFAULT_TSS_CONFIG,
                        true,
                        source,
                        false,
                        sourceSigner,
                        9));
        given(sourceSigner.constructionId()).willReturn(7L);
        given(sourceSigner.verificationKey()).willReturn(Bytes.wrap("different source key"));
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.reconcile(
                        activeRosters,
                        CURRENT_VK,
                        store,
                        CONSENSUS_NOW,
                        DEFAULT_TSS_CONFIG,
                        true,
                        source,
                        false,
                        sourceSigner,
                        9));
        verifyNoInteractions(store, component, controllers, controller);
    }

    @Test
    void doesNothingAfterIneffectualHandoff() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(HANDOFF);

        subject.reconcile(
                activeRosters, null, store, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, true, HintsConstruction.DEFAULT, false);

        verify(store, never()).getConstructionFor(activeRosters);
    }

    @Test
    void usesLibraryForLedgerIdOfGroundingProof() {
        withMockSubject();
        final var history = new History(Bytes.wrap("ADDRESS_BOOK_HASH"), CURRENT_VK);
        final var ledgerId = Bytes.wrap("LEDGER_ID");
        given(component.library()).willReturn(library);
        given(library.ledgerIdOf(history)).willReturn(ledgerId);

        assertEquals(
                ledgerId,
                subject.ledgerIdOf(
                        HistoryProof.newBuilder().targetHistory(history).build()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEV", "TEST", "PROD"})
    void respectsProfileWhenInitializingGenesisFromStartupNetworkTssMetadata(final String profile) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("hedera.profiles.active", profile)
                .getOrCreateConfig();
        final var ledgerId = Bytes.wrap("LEDGER");
        final var chainOfTrustProof =
                ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("PROOF")).build();
        final var proofKey = Bytes.wrap("startup-proof-key");
        final var targetProof = HistoryProof.newBuilder()
                .targetHistory(History.newBuilder().metadata(CURRENT_VK))
                .chainOfTrustProof(chainOfTrustProof)
                .build();
        final var activeConstruction = HistoryProofConstruction.newBuilder()
                .constructionId(123L)
                .targetProof(targetProof)
                .build();
        final var network = Network.newBuilder()
                .ledgerId(ledgerId)
                .tssMetadata(TssMetadata.newBuilder().activeProofConstruction(activeConstruction))
                .nodeTssMetadata(
                        NodeTssMetadata.newBuilder().schnorrPublicKey(proofKey).build())
                .build();
        subject = new HistoryServiceImpl(component, () -> network);
        given(writableStates.<ProtoBytes>getSingleton(V071HistorySchema.LEDGER_ID_STATE_ID))
                .willReturn(ledgerIdState);
        given(writableStates.<HistoryProofConstruction>getSingleton(
                        V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(writableStates.<HistoryProofConstruction>getSingleton(V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        if (!profile.equals("PROD")) {
            given(writableStates.<NodeId, ProofKeySet>get(V071HistorySchema.PROOF_KEY_SETS_STATE_ID))
                    .willReturn(proofKeys);
        }

        assertTrue(subject.doGenesisSetup(writableStates, config));

        verify(nextConstructionState).put(HistoryProofConstruction.DEFAULT);
        if (profile.equals("PROD")) {
            verify(ledgerIdState).put(ProtoBytes.DEFAULT);
            verify(activeConstructionState).put(HistoryProofConstruction.DEFAULT);
            verify(writableStates, never()).get(V071HistorySchema.PROOF_KEY_SETS_STATE_ID);
            verifyNoInteractions(proofKeys);
            assertFalse(subject.isReady());
        } else {
            verify(ledgerIdState).put(new ProtoBytes(ledgerId));
            verify(activeConstructionState).put(activeConstruction);
            verify(proofKeys)
                    .put(
                            new NodeId(0L),
                            ProofKeySet.newBuilder()
                                    .adoptionTime(asTimestamp(Instant.EPOCH))
                                    .key(proofKey)
                                    .build());
            assertTrue(subject.isReady());
            assertEquals(chainOfTrustProof, subject.getCurrentChainOfTrustProof(CURRENT_VK));
        }
    }

    @Test
    void doesGenesisSetupWithoutStartupNetwork() {
        subject = new HistoryServiceImpl(component, () -> null);
        given(writableStates.<ProtoBytes>getSingleton(V071HistorySchema.LEDGER_ID_STATE_ID))
                .willReturn(ledgerIdState);
        given(writableStates.<HistoryProofConstruction>getSingleton(
                        V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(writableStates.<HistoryProofConstruction>getSingleton(V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);

        assertTrue(subject.doGenesisSetup(writableStates, DEFAULT_CONFIG));

        verify(ledgerIdState).put(ProtoBytes.DEFAULT);
        verify(activeConstructionState).put(HistoryProofConstruction.DEFAULT);
        verify(nextConstructionState).put(HistoryProofConstruction.DEFAULT);
        assertFalse(subject.isReady());
    }

    private void withLiveSubject() {
        subject = new HistoryServiceImpl(NO_OP_METRICS, ForkJoinPool.commonPool(), appContext, library);
    }

    private void withMockSubject() {
        subject = new HistoryServiceImpl(component);
    }

    private static HintsConstruction completedHints(final long constructionId, final Bytes vk) {
        return HintsConstruction.newBuilder()
                .constructionId(constructionId)
                .hintsScheme(HintsScheme.newBuilder().preprocessedKeys(new PreprocessedKeys(Bytes.wrap("AK"), vk)))
                .build();
    }
}
