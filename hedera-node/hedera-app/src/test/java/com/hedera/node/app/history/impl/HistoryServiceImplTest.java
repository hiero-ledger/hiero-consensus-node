// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
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
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
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

    private void withLiveSubject() {
        subject = new HistoryServiceImpl(NO_OP_METRICS, ForkJoinPool.commonPool(), appContext, library);
    }

    private void withMockSubject() {
        subject = new HistoryServiceImpl(component);
    }
}
