// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsControllersTest {
    private static final HintsConstruction ONE_CONSTRUCTION =
            HintsConstruction.newBuilder().constructionId(1L).build();
    private static final Roster CURRENT_ROSTER = new Roster(List.of(
            RosterEntry.newBuilder().nodeId(1L).build(),
            RosterEntry.newBuilder().nodeId(2L).build()));

    @Mock
    private Executor executor;

    @Mock
    private HintsKeyAccessor keyAccessor;

    @Mock
    private NodeInfo selfNodeInfo;

    @Mock
    private HintsLibrary library;

    @Mock
    private HintsSubmissions submissions;

    @Mock
    private Supplier<NodeInfo> selfNodeInfoSupplier;

    @Mock
    private ActiveRosters activeRosters;

    @Mock
    private RosterTransitionWeights weights;

    @Mock
    private WritableHintsStore hintsStore;

    @Mock
    private HintsContext context;

    @Mock
    private OnHintsFinished onHintsFinished;

    @Mock
    private HintsController controller;

    private HintsControllers subject;

    @BeforeEach
    void setUp() {
        subject = new HintsControllers(
                executor,
                keyAccessor,
                library,
                submissions,
                context,
                selfNodeInfoSupplier,
                HederaTestConfigBuilder::createConfig,
                onHintsFinished);
    }

    @Test
    void getsAndCreatesInertControllersAsExpected() {
        given(activeRosters.transitionWeights(null)).willReturn(weights);

        final var twoConstruction =
                HintsConstruction.newBuilder().constructionId(2L).build();

        assertTrue(subject.getInProgressById(2L).isEmpty());
        final var firstController =
                subject.getOrCreateFor(activeRosters, ONE_CONSTRUCTION, hintsStore, HintsConstruction.DEFAULT);
        assertTrue(subject.getInProgressById(1L).isEmpty());
        assertTrue(subject.getInProgressById(2L).isEmpty());
        assertInstanceOf(InertHintsController.class, firstController);
        final var secondController =
                subject.getOrCreateFor(activeRosters, twoConstruction, hintsStore, HintsConstruction.DEFAULT);
        assertNotSame(firstController, secondController);
        assertInstanceOf(InertHintsController.class, secondController);
    }

    @Test
    void returnsActiveControllerWhenSourceNodesHaveTargetThresholdWeight() {
        given(activeRosters.transitionWeights(null)).willReturn(weights);
        given(weights.sourceNodesHaveTargetThreshold()).willReturn(true);
        given(keyAccessor.getOrCreateBlsPrivateKey(1L)).willReturn(Bytes.EMPTY);
        given(selfNodeInfoSupplier.get()).willReturn(selfNodeInfo);
        given(hintsStore.getCrsState()).willReturn(CRSState.DEFAULT);
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(Set.of(1L)));
        given(activeRosters.currentRoster()).willReturn(CURRENT_ROSTER);
        given(hintsStore.getVotes(1L, Set.of(1L, 2L))).willReturn(Map.of());

        final var controller =
                subject.getOrCreateFor(activeRosters, ONE_CONSTRUCTION, hintsStore, HintsConstruction.DEFAULT);

        assertInstanceOf(HintsControllerImpl.class, controller);
        verify(hintsStore).getVotes(1L, Set.of(1L, 2L));
    }

    @Test
    void stopCancelsAndRefreshesControllerForSameConstructionId() throws Exception {
        final var learnedConstruction = HintsConstruction.newBuilder()
                .constructionId(1L)
                .preprocessingStartTime(asTimestamp(Instant.EPOCH))
                .build();
        given(controller.constructionId()).willReturn(1L);
        setController(controller);

        final var staleController =
                subject.getOrCreateFor(activeRosters, learnedConstruction, hintsStore, HintsConstruction.DEFAULT);
        assertSame(controller, staleController);

        subject.stop();
        subject.stop();

        verify(controller).cancelPendingWork();
        given(activeRosters.transitionWeights(null)).willReturn(weights);
        final var refreshedController =
                subject.getOrCreateFor(activeRosters, learnedConstruction, hintsStore, HintsConstruction.DEFAULT);
        assertNotSame(staleController, refreshedController);
    }

    private void setController(final HintsController controller) throws Exception {
        final var field = HintsControllers.class.getDeclaredField("controller");
        field.setAccessible(true);
        field.set(subject, controller);
    }
}
