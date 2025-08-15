// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.roster.RosterTransitionWeights;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
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
    private HistorySubmissions submissions;

    @Mock
    private Supplier<NodeInfo> selfNodeInfoSupplier;

    @Mock
    private Consumer<HistoryProof> proofConsumer;

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
                new ProofControllers(executor, keyAccessor, library, submissions, selfNodeInfoSupplier, proofConsumer);
    }

    @Test
    void getsAndCreatesInertControllersAsExpected() {
        given(activeRosters.transitionWeights()).willReturn(weights);

        final var twoConstruction =
                HistoryProofConstruction.newBuilder().constructionId(2L).build();

        assertTrue(subject.getAnyInProgress().isEmpty());
        final var firstController = subject.getOrCreateFor(activeRosters, ONE_CONSTRUCTION, historyStore);
        assertTrue(subject.getAnyInProgress().isEmpty());
        assertTrue(subject.getInProgressById(1L).isEmpty());
        assertTrue(subject.getInProgressById(2L).isEmpty());
        assertInstanceOf(InertProofController.class, firstController);
        final var secondController = subject.getOrCreateFor(activeRosters, twoConstruction, historyStore);
        assertNotSame(firstController, secondController);
        assertInstanceOf(InertProofController.class, secondController);
    }

    @Test
    void returnsActiveControllerWhenSourceNodesHaveTargetThresholdWeight() {
        given(activeRosters.transitionWeights()).willReturn(weights);
        given(weights.sourceNodesHaveTargetThreshold()).willReturn(true);
        given(keyAccessor.getOrCreateSchnorrKeyPair(1L)).willReturn(MOCK_KEY_PAIR);
        given(selfNodeInfoSupplier.get()).willReturn(selfNodeInfo);

        final var controller = subject.getOrCreateFor(activeRosters, ONE_CONSTRUCTION, historyStore);

        assertInstanceOf(ProofControllerImpl.class, controller);
    }
}
