// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.history.schemas.V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.LEDGER_ID_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.BOOTSTRAP;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.HANDOFF;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.TRANSITION;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.PostUpgradeContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.time.Instant;
import java.util.concurrent.ForkJoinPool;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoryServiceImplTest {
    private static final Bytes CURRENT_VK = Bytes.wrap("Z");
    private static final Metrics NO_OP_METRICS = new NoOpMetrics();
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final TssConfig DEFAULT_TSS_CONFIG = DEFAULT_CONFIG.getConfigData(TssConfig.class);
    private static final String HASH_HEX = "aa".repeat(48);

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
    private PostUpgradeContext postUpgradeContext;

    @Mock
    private WritableSingletonState<ProtoBytes> ledgerIdState;

    @Mock
    private WritableSingletonState<HistoryProofConstruction> activeConstructionState;

    @Mock
    private WritableSingletonState<HistoryProofConstruction> nextConstructionState;

    @Mock
    private WritableSingletonState<ProtoBytes> wrapsHashState;

    private HistoryServiceImpl subject;

    @Test
    void metadataAsExpected() {
        withLiveSubject();
        assertEquals(HistoryService.NAME, subject.getServiceName());
        assertEquals(HistoryService.MIGRATION_ORDER, subject.migrationOrder());
    }

    @Test
    void notReadyUntilHistoryProofSetWithChainOfTrust() {
        withLiveSubject();
        assertFalse(subject.isReady());
        subject.setLatestHistoryProof(HistoryProof.DEFAULT);
        assertFalse(subject.isReady());
        subject.setLatestHistoryProof(HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.DEFAULT)
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
    void handoffIsNoop() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(HANDOFF);
        subject.reconcile(activeRosters, Bytes.EMPTY, store, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, true, null);
    }

    @Test
    void noopReconciliationIfBootstrapHasProof() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(BOOTSTRAP);
        given(store.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, DEFAULT_TSS_CONFIG))
                .willReturn(HistoryProofConstruction.newBuilder()
                        .targetProof(HistoryProof.DEFAULT)
                        .build());

        subject.reconcile(activeRosters, null, store, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, true, null);

        verifyNoMoreInteractions(component);
    }

    @Test
    void activeReconciliationIfTransitionHasNoProofYet() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(TRANSITION);
        given(store.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, DEFAULT_TSS_CONFIG))
                .willReturn(HistoryProofConstruction.DEFAULT);
        given(store.getActiveConstruction()).willReturn(HistoryProofConstruction.DEFAULT);
        given(component.controllers()).willReturn(controllers);
        given(controllers.getOrCreateFor(
                        activeRosters,
                        HistoryProofConstruction.DEFAULT,
                        store,
                        HintsConstruction.DEFAULT,
                        HistoryProofConstruction.DEFAULT,
                        DEFAULT_CONFIG.getConfigData(TssConfig.class)))
                .willReturn(controller);

        subject.reconcile(
                activeRosters, CURRENT_VK, store, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, true, HintsConstruction.DEFAULT);

        verify(controller).advanceConstruction(CONSENSUS_NOW, CURRENT_VK, store, true, DEFAULT_TSS_CONFIG);
    }

    @Test
    void doesNothingAfterIneffectualHandoff() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(HANDOFF);

        subject.reconcile(
                activeRosters, null, store, CONSENSUS_NOW, DEFAULT_TSS_CONFIG, true, HintsConstruction.DEFAULT);

        verify(store, never()).getConstructionFor(activeRosters);
    }

    @Test
    void wrapsWrapsKeyForProofVerification() {
        withMockSubject();
        final var mockKey = "ABCDEFGH".getBytes(UTF_8);
        given(component.library()).willReturn(library);
        given(library.wrapsVerificationKey()).willReturn(mockKey);
        assertEquals(Bytes.wrap(mockKey), subject.historyProofVerificationKey());
    }

    @Test
    void doesPostUpgradeSetupInitializesMissingSingletonsAndConfiguredHash() {
        withMockSubject();
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("tss.historyEnabled", "true")
                .withValue("tss.wrapsProvingKeyHash", HASH_HEX)
                .getOrCreateConfig();
        given(postUpgradeContext.configuration()).willReturn(configuration);
        given(writableStates.<ProtoBytes>getSingleton(LEDGER_ID_STATE_ID)).willReturn(ledgerIdState);
        given(ledgerIdState.get()).willReturn(null);
        given(writableStates.<HistoryProofConstruction>getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(null, HistoryProofConstruction.DEFAULT);
        given(writableStates.<HistoryProofConstruction>getSingleton(NEXT_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(nextConstructionState.get()).willReturn(null);
        given(writableStates.<ProtoBytes>getSingleton(WRAPS_PROVING_KEY_HASH_STATE_ID))
                .willReturn(wrapsHashState);
        given(wrapsHashState.get()).willReturn(null);

        assertTrue(subject.doPostUpgradeSetup(writableStates, postUpgradeContext));

        verify(ledgerIdState).put(ProtoBytes.DEFAULT);
        verify(activeConstructionState).put(HistoryProofConstruction.DEFAULT);
        verify(nextConstructionState).put(HistoryProofConstruction.DEFAULT);
        verify(wrapsHashState).put(ProtoBytes.DEFAULT);
        verify(wrapsHashState)
                .put(ProtoBytes.newBuilder().value(Bytes.fromHex(HASH_HEX)).build());
    }

    @Test
    void doesPostUpgradeSetupInitializesLatestHistoryProofFromActiveConstruction() {
        withMockSubject();
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("tss.historyEnabled", "true")
                .withValue("tss.wrapsProvingKeyHash", "")
                .getOrCreateConfig();
        final var targetProof = HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.DEFAULT)
                .build();
        final var activeConstruction =
                HistoryProofConstruction.newBuilder().targetProof(targetProof).build();
        given(postUpgradeContext.configuration()).willReturn(configuration);
        given(writableStates.<ProtoBytes>getSingleton(LEDGER_ID_STATE_ID)).willReturn(ledgerIdState);
        given(ledgerIdState.get()).willReturn(ProtoBytes.DEFAULT);
        given(writableStates.<HistoryProofConstruction>getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(activeConstruction);
        given(writableStates.<HistoryProofConstruction>getSingleton(NEXT_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(nextConstructionState.get()).willReturn(HistoryProofConstruction.DEFAULT);
        given(writableStates.<ProtoBytes>getSingleton(WRAPS_PROVING_KEY_HASH_STATE_ID))
                .willReturn(wrapsHashState);
        given(wrapsHashState.get()).willReturn(ProtoBytes.DEFAULT);

        assertFalse(subject.doPostUpgradeSetup(writableStates, postUpgradeContext));

        assertTrue(subject.isReady());
    }

    private void withLiveSubject() {
        subject = new HistoryServiceImpl(NO_OP_METRICS, ForkJoinPool.commonPool(), appContext, library);
    }

    private void withMockSubject() {
        subject = new HistoryServiceImpl(component);
    }
}
