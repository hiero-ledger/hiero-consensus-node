// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.roster.ActiveRosters.Phase.BOOTSTRAP;
import static com.hedera.node.app.roster.ActiveRosters.Phase.HANDOFF;
import static com.hedera.node.app.roster.ActiveRosters.Phase.TRANSITION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.metrics.api.Metrics;
import java.time.Instant;
import java.util.concurrent.ForkJoinPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoryServiceImplTest {
    private static final Bytes CURRENT_VK = Bytes.wrap("Z");
    private static final Metrics NO_OP_METRICS = new NoOpMetrics();
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);

    @Mock
    private AppContext appContext;

    @Mock
    private HistoryServiceComponent component;

    @Mock
    private ProofControllers controllers;

    @Mock
    private ProofController controller;

    @Mock
    private TssConfig tssConfig;

    @Mock
    private ActiveRosters activeRosters;

    @Mock
    private HistoryLibraryCodec codec;

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
    void onlyReadyGivenProof() {
        withLiveSubject();
        assertFalse(subject.isReady());
        subject.accept(HistoryProof.DEFAULT);
        assertTrue(subject.isReady());
    }

    @Test
    void refusesToProveMismatchedMetadata() {
        withLiveSubject();
        final var oldVk = Bytes.wrap("X");
        final var currentProof = HistoryProof.newBuilder()
                .targetHistory(History.newBuilder().metadata(CURRENT_VK))
                .build();

        subject.accept(currentProof);
        assertThrows(IllegalArgumentException.class, () -> subject.getCurrentProof(oldVk));
        assertEquals(currentProof.proof(), subject.getCurrentProof(CURRENT_VK));
    }

    @Test
    void usesComponentForHandlers() {
        withMockSubject();
        given(component.handlers()).willReturn(handlers);
        assertSame(handlers, subject.handlers());
    }

    @Test
    void updatesActiveProofAfterEffectiveHandoff() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(HANDOFF);
        given(store.purgeStateAfterHandoff(activeRosters)).willReturn(true);
        final var currentVk = Bytes.wrap("Z");
        final var construction = HistoryProofConstruction.newBuilder()
                .targetProof(HistoryProof.newBuilder()
                        .targetHistory(History.newBuilder().metadata(currentVk)))
                .build();
        given(store.getConstructionFor(activeRosters)).willReturn(construction);

        subject.reconcile(activeRosters, currentVk, store, CONSENSUS_NOW, tssConfig);

        assertDoesNotThrow(() -> subject.getCurrentProof(currentVk));
    }

    @Test
    void noopReconciliationIfBootstrapHasProof() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(BOOTSTRAP);
        given(store.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, tssConfig))
                .willReturn(HistoryProofConstruction.newBuilder()
                        .targetProof(HistoryProof.DEFAULT)
                        .build());

        subject.reconcile(activeRosters, null, store, CONSENSUS_NOW, tssConfig);

        verifyNoMoreInteractions(component);
    }

    @Test
    void activeReconciliationIfTransitionHasNoProofYet() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(TRANSITION);
        given(store.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, tssConfig))
                .willReturn(HistoryProofConstruction.DEFAULT);
        given(component.controllers()).willReturn(controllers);
        given(controllers.getOrCreateFor(activeRosters, HistoryProofConstruction.DEFAULT, store))
                .willReturn(controller);

        subject.reconcile(activeRosters, CURRENT_VK, store, CONSENSUS_NOW, tssConfig);

        verify(controller).advanceConstruction(CONSENSUS_NOW, CURRENT_VK, store);
    }

    @Test
    void doesNothingAfterIneffectualHandoff() {
        withMockSubject();
        given(activeRosters.phase()).willReturn(HANDOFF);

        subject.reconcile(activeRosters, null, store, CONSENSUS_NOW, tssConfig);

        verify(store, never()).getConstructionFor(activeRosters);
    }

    private void withLiveSubject() {
        subject = new HistoryServiceImpl(
                NO_OP_METRICS, ForkJoinPool.commonPool(), appContext, library, codec, DEFAULT_CONFIG);
    }

    private void withMockSubject() {
        subject = new HistoryServiceImpl(component, DEFAULT_CONFIG);
    }
}
