// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProofControllersTest {
    private static final ProofKeysAccessorImpl.SchnorrKeyPair MOCK_KEY_PAIR =
            new ProofKeysAccessorImpl.SchnorrKeyPair(Bytes.EMPTY, Bytes.EMPTY);
    private static final HistoryProofConstruction ONE_CONSTRUCTION =
            HistoryProofConstruction.newBuilder().constructionId(1L).build();

    @Mock
    private Executor executor;

    @Mock
    private ProofKeysAccessor keyAccessor;

    @Mock
    private NodeInfo selfNodeInfo;

    @Mock
    private HistoryLibrary library;

    @Mock
    private HistoryService historyService;

    @Mock
    private HistorySubmissions submissions;

    @Mock
    private Supplier<NodeInfo> selfNodeInfoSupplier;

    @Mock
    private ActiveRosters activeRosters;

    @Mock
    private RosterTransitionWeights weights;

    @Mock
    private ReadableHistoryStore historyStore;

    private ProofControllers subject;

    @BeforeEach
    void setUp() {
        subject =
                new ProofControllers(executor, keyAccessor, library, submissions, selfNodeInfoSupplier, historyService);
    }

    @Test
    void getsAndCreatesInertControllersAsExpected() {
        given(activeRosters.transitionWeights(null)).willReturn(weights);

        final var twoConstruction =
                HistoryProofConstruction.newBuilder().constructionId(2L).build();

        assertTrue(subject.getAnyInProgress().isEmpty());
        final var firstController =
                subject.getOrCreateFor(activeRosters, ONE_CONSTRUCTION, historyStore, HintsConstruction.DEFAULT, true);
        assertTrue(subject.getAnyInProgress().isEmpty());
        assertTrue(subject.getInProgressById(1L).isEmpty());
        assertTrue(subject.getInProgressById(2L).isEmpty());
        assertInstanceOf(InertProofController.class, firstController);
        final var secondController =
                subject.getOrCreateFor(activeRosters, twoConstruction, historyStore, HintsConstruction.DEFAULT, true);
        assertNotSame(firstController, secondController);
        assertInstanceOf(InertProofController.class, secondController);
    }

    @Test
    void returnsActiveControllerWhenSourceNodesHaveTargetThresholdWeight() {
        given(activeRosters.transitionWeights(null)).willReturn(weights);
        given(weights.sourceNodesHaveTargetThreshold()).willReturn(true);
        given(keyAccessor.getOrCreateSchnorrKeyPair(1L)).willReturn(MOCK_KEY_PAIR);
        given(selfNodeInfoSupplier.get()).willReturn(selfNodeInfo);

        final var controller =
                subject.getOrCreateFor(activeRosters, ONE_CONSTRUCTION, historyStore, HintsConstruction.DEFAULT, true);

        assertInstanceOf(ProofControllerImpl.class, controller);
    }
}
