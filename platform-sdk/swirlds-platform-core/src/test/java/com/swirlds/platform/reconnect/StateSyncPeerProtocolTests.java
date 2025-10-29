// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.state.signed.ReservedSignedState.createNullReservation;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig_;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.gossip.FallenBehindMonitor;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.protocol.PeerProtocol;
import com.swirlds.platform.network.protocol.Protocol;
import com.swirlds.platform.network.protocol.ReservedSignedStatePromise;
import com.swirlds.platform.network.protocol.StateSyncProtocol;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.state.MerkleNodeState;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.hiero.base.ValueReference;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 */
class StateSyncPeerProtocolTests {
    private static final NodeId PEER_ID = NodeId.of(1L);

    private StateSyncThrottle teacherThrottle;
    private ReconnectMetrics reconnectMetrics;
    private ReservedSignedStatePromise reservedSignedStatePromise;

    private static Stream<Arguments> initiateParams() {
        return Stream.of(
                Arguments.of(new InitiateParams(
                        true, true, true, "Permit acquired and peer is a reconnect neighbor, initiate")),
                Arguments.of(new InitiateParams(false, true, false, "Permit not acquired, do not initiate")),
                Arguments.of(
                        new InitiateParams(true, false, false, "Peer is not a reconnect neighbor, do not initiate")),
                Arguments.of(new InitiateParams(
                        false,
                        false,
                        false,
                        "Permit not acquired and peer is not a reconnect neighbor, do not initiate")));
    }

    private record InitiateParams(
            boolean getsPermit, boolean isReconnectNeighbor, boolean shouldInitiate, String desc) {
        @Override
        public String toString() {
            return desc;
        }
    }

    private static Stream<Arguments> acceptParams() {
        final List<Arguments> arguments = new ArrayList<>();

        for (final boolean teacherIsThrottled : List.of(true, false)) {
            for (final boolean selfIsBehind : List.of(true, false)) {
                for (final boolean teacherHasValidState : List.of(true, false)) {
                    arguments.add(
                            Arguments.of(new AcceptParams(teacherIsThrottled, selfIsBehind, teacherHasValidState)));
                }
            }
        }

        return arguments.stream();
    }

    private record AcceptParams(boolean teacherIsThrottled, boolean selfIsBehind, boolean teacherHasValidState) {

        public boolean shouldAccept() {
            return !teacherIsThrottled && !selfIsBehind && teacherHasValidState;
        }

        @Override
        public String toString() {
            return (teacherIsThrottled ? "throttled teacher" : "un-throttled teacher") + ", "
                    + (selfIsBehind ? "teacher is behind" : "teacher not behind")
                    + ", " + (teacherHasValidState ? "teacher has valid state" : "teacher has no valid state");
        }
    }

    @BeforeEach
    void setup() {
        reservedSignedStatePromise = mock(ReservedSignedStatePromise.class);
        when(reservedSignedStatePromise.tryBlock()).thenReturn(true);

        teacherThrottle = mock(StateSyncThrottle.class);
        when(teacherThrottle.initiateReconnect(any())).thenReturn(true);

        var nopMetrics = new NoOpMetrics();
        reconnectMetrics = mock(ReconnectMetrics.class);

        when(reconnectMetrics.getMetrics()).thenReturn(nopMetrics);
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @DisplayName("Test the conditions under which the protocol should and should not be initiated")
    @ParameterizedTest
    @MethodSource("initiateParams")
    void shouldInitiateTest(final InitiateParams params) {
        when(reservedSignedStatePromise.acquire()).thenReturn(params.getsPermit);

        final List<NodeId> neighborsForReconnect = LongStream.range(0L, 10L)
                .filter(id -> id != PEER_ID.id() || params.isReconnectNeighbor)
                .mapToObj(NodeId::of)
                .toList();

        final FallenBehindMonitor fallenBehindManager = mock(FallenBehindMonitor.class);
        when(fallenBehindManager.wasReportedByPeer(any()))
                .thenAnswer(a -> neighborsForReconnect.contains(a.getArgument(0, NodeId.class)));

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final Protocol reconnectProtocol = new StateSyncProtocol(
                platformContext,
                getStaticThreadManager(),
                mock(StateSyncThrottle.class),
                () -> null,
                Duration.of(100, ChronoUnit.MILLIS),
                reconnectMetrics,
                fallenBehindManager,
                TEST_PLATFORM_STATE_FACADE,
                reservedSignedStatePromise,
                mock(SwirldStateManager.class),
                a -> null);
        reconnectProtocol.updatePlatformStatus(ACTIVE);

        assertEquals(
                params.shouldInitiate,
                reconnectProtocol.createPeerInstance(PEER_ID).shouldInitiate(),
                "unexpected initiation result");
    }

    @DisplayName("Test the conditions under which the protocol should accept protocol initiation")
    @ParameterizedTest
    @MethodSource("acceptParams")
    void testShouldAccept(final AcceptParams params) {
        final StateSyncThrottle teacherThrottle = mock(StateSyncThrottle.class);
        when(teacherThrottle.initiateReconnect(any())).thenReturn(!params.teacherIsThrottled);

        final FallenBehindMonitor fallenBehindManager = mock(FallenBehindMonitor.class);
        when(fallenBehindManager.hasFallenBehind()).thenReturn(params.selfIsBehind);

        final SignedState signedState;
        if (params.teacherHasValidState) {
            signedState = spy(new RandomSignedStateGenerator().build());
            when(signedState.isComplete()).thenReturn(true);
        } else {
            signedState = null;
        }

        final ReservedSignedState reservedSignedState =
                signedState == null ? createNullReservation() : signedState.reserve("test");

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final Protocol reconnectProtocol = new StateSyncProtocol(
                platformContext,
                getStaticThreadManager(),
                teacherThrottle,
                () -> reservedSignedState,
                Duration.of(100, ChronoUnit.MILLIS),
                reconnectMetrics,
                fallenBehindManager,
                TEST_PLATFORM_STATE_FACADE,
                reservedSignedStatePromise,
                mock(SwirldStateManager.class),
                a -> null);
        reconnectProtocol.updatePlatformStatus(ACTIVE);
        assertEquals(
                params.shouldAccept(),
                reconnectProtocol.createPeerInstance(PEER_ID).shouldAccept(),
                "unexpected protocol acceptance");
    }

    @DisplayName("Tests if teacher throttle gets released")
    @Test
    void testTeacherThrottleReleased() {
        final FallenBehindMonitor fallenBehindManager = mock(FallenBehindMonitor.class);
        final Configuration config = new TestConfigBuilder()
                // we don't want the time based throttle to interfere
                .withValue(ReconnectConfig_.MINIMUM_TIME_BETWEEN_RECONNECTS, "0s")
                .getOrCreateConfig();
        final StateSyncThrottle reconnectThrottle =
                new StateSyncThrottle(config.getConfigData(ReconnectConfig.class), Time.getCurrent());

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final NodeId node1 = NodeId.of(1L);
        final NodeId node2 = NodeId.of(2L);

        final StateSyncPeerProtocol peer1 = new StateSyncPeerProtocol(
                platformContext,
                getStaticThreadManager(),
                node1,
                reconnectThrottle,
                () -> null,
                Duration.of(100, ChronoUnit.MILLIS),
                reconnectMetrics,
                fallenBehindManager,
                () -> ACTIVE,
                Time.getCurrent(),
                TEST_PLATFORM_STATE_FACADE,
                reservedSignedStatePromise,
                mock(SwirldStateManager.class),
                a -> null);
        final SignedState signedState = spy(new RandomSignedStateGenerator().build());
        when(signedState.isComplete()).thenReturn(true);
        final MerkleNodeState state = mock(MerkleNodeState.class);
        when(signedState.getState()).thenReturn(state);

        final ReservedSignedState reservedSignedState = signedState.reserve("test");

        final StateSyncPeerProtocol peer2 = new StateSyncPeerProtocol(
                platformContext,
                getStaticThreadManager(),
                node2,
                reconnectThrottle,
                () -> reservedSignedState,
                Duration.of(100, ChronoUnit.MILLIS),
                reconnectMetrics,
                fallenBehindManager,
                () -> ACTIVE,
                Time.getCurrent(),
                TEST_PLATFORM_STATE_FACADE,
                reservedSignedStatePromise,
                mock(SwirldStateManager.class),
                a -> null);

        // pretend we have fallen behind
        when(fallenBehindManager.hasFallenBehind()).thenReturn(true);
        assertFalse(peer1.shouldAccept(), "we should not accept because we have fallen behind");
        assertFalse(peer2.shouldAccept(), "we should not accept because we have fallen behind");

        // now we have not fallen behind
        when(fallenBehindManager.hasFallenBehind()).thenReturn(false);
        assertTrue(peer2.shouldAccept(), "we should accept because we have not fallen behind");
    }

    @DisplayName("Tests if the reconnect learner permit gets released")
    @Test
    void testPermitReleased() {
        Thread t = null;
        try {
            final FallenBehindMonitor fallenBehindManager = mock(FallenBehindMonitor.class);
            when(fallenBehindManager.wasReportedByPeer(any())).thenReturn(false);
            ReservedSignedStatePromise reservedSignedStatePromise = new ReservedSignedStatePromise();

            final PlatformContext platformContext =
                    TestPlatformContextBuilder.create().build();

            final Protocol reconnectProtocol = new StateSyncProtocol(
                    platformContext,
                    getStaticThreadManager(),
                    mock(StateSyncThrottle.class),
                    () -> null,
                    Duration.of(100, ChronoUnit.MILLIS),
                    reconnectMetrics,
                    fallenBehindManager,
                    TEST_PLATFORM_STATE_FACADE,
                    reservedSignedStatePromise,
                    mock(SwirldStateManager.class),
                    a -> null);
            reconnectProtocol.updatePlatformStatus(ACTIVE);
            assertFalse(
                    reservedSignedStatePromise.acquire(),
                    "the while loop should have acquired the permit, so it should not be available");

            t = new Thread(reservedSignedStatePromise::await);
            t.start();

            Thread.sleep(500);
            assertFalse(
                    reconnectProtocol.createPeerInstance(PEER_ID).shouldInitiate(),
                    "we expect that a reconnect should not be initiated because of FallenBehindMonitor");
            assertTrue(reservedSignedStatePromise.acquire(), "a permit should still be available for other peers");

        } catch (InterruptedException e) {
            fail();
        } finally {
            if (t != null) t.interrupt();
        }
    }

    @Test
    @DisplayName("Aborted Learner")
    void abortedLearner() {
        when(reservedSignedStatePromise.acquire()).thenReturn(true);
        final ValueReference<Boolean> permitCancelled = new ValueReference<>(false);
        doAnswer(invocation -> {
                    assertFalse(permitCancelled.getValue(), "permit should only be cancelled once");
                    permitCancelled.setValue(true);
                    return null;
                })
                .when(reservedSignedStatePromise)
                .release();

        final FallenBehindMonitor fallenBehindManager = mock(FallenBehindMonitor.class);
        when(fallenBehindManager.hasFallenBehind()).thenReturn(true);
        when(fallenBehindManager.wasReportedByPeer(any())).thenReturn(true);

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final Protocol reconnectProtocol = new StateSyncProtocol(
                platformContext,
                getStaticThreadManager(),
                mock(StateSyncThrottle.class),
                () -> mock(ReservedSignedState.class),
                Duration.of(100, ChronoUnit.MILLIS),
                reconnectMetrics,
                fallenBehindManager,
                TEST_PLATFORM_STATE_FACADE,
                reservedSignedStatePromise,
                mock(SwirldStateManager.class),
                a -> null);
        reconnectProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = reconnectProtocol.createPeerInstance(NodeId.of(0));
        assertTrue(peerProtocol.shouldInitiate());
        peerProtocol.initiateFailed();

        assertTrue(permitCancelled.getValue(), "permit should have been cancelled");
    }

    @Test
    @DisplayName("Aborted Teacher")
    void abortedTeacher() {
        final StateSyncThrottle reconnectThrottle = mock(StateSyncThrottle.class);
        when(reconnectThrottle.initiateReconnect(any())).thenReturn(true);
        final ValueReference<Boolean> throttleReleased = new ValueReference<>(false);
        doAnswer(invocation -> {
                    assertFalse(throttleReleased.getValue(), "throttle should not be released twice");
                    throttleReleased.setValue(true);
                    return null;
                })
                .when(reconnectThrottle)
                .reconnectAttemptFinished();

        final FallenBehindMonitor fallenBehindManager = mock(FallenBehindMonitor.class);
        when(fallenBehindManager.hasFallenBehind()).thenReturn(false);

        final SignedState signedState = spy(new RandomSignedStateGenerator().build());
        when(signedState.isComplete()).thenReturn(true);

        final ReservedSignedState reservedSignedState = signedState.reserve("test");

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final Protocol reconnectProtocol = new StateSyncProtocol(
                platformContext,
                getStaticThreadManager(),
                reconnectThrottle,
                () -> reservedSignedState,
                Duration.of(100, ChronoUnit.MILLIS),
                reconnectMetrics,
                fallenBehindManager,
                TEST_PLATFORM_STATE_FACADE,
                reservedSignedStatePromise,
                mock(SwirldStateManager.class),
                a -> null);
        reconnectProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = reconnectProtocol.createPeerInstance(NodeId.of(0));
        assertTrue(peerProtocol.shouldAccept());
        peerProtocol.acceptFailed();

        assertTrue(throttleReleased.getValue(), "throttle should be released");
        assertEquals(-1, signedState.getReservationCount(), "state should be released");
    }

    @Test
    @DisplayName("Teacher Has No Signed State")
    void teacherHasNoSignedState() {
        final StateSyncThrottle reconnectThrottle = mock(StateSyncThrottle.class);
        doAnswer(invocation -> {
                    fail("throttle should not be engaged if there is not available state");
                    return null;
                })
                .when(reconnectThrottle)
                .initiateReconnect(any());

        final FallenBehindMonitor fallenBehindManager = mock(FallenBehindMonitor.class);
        when(fallenBehindManager.hasFallenBehind()).thenReturn(false);

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final Protocol reconnectProtocol = new StateSyncProtocol(
                platformContext,
                getStaticThreadManager(),
                reconnectThrottle,
                ReservedSignedState::createNullReservation,
                Duration.of(100, ChronoUnit.MILLIS),
                reconnectMetrics,
                fallenBehindManager,
                TEST_PLATFORM_STATE_FACADE,
                reservedSignedStatePromise,
                mock(SwirldStateManager.class),
                a -> null);
        reconnectProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = reconnectProtocol.createPeerInstance(NodeId.of(0));
        assertFalse(peerProtocol.shouldAccept());
    }

    @Test
    @DisplayName("Teacher doesn't have a status of ACTIVE")
    void teacherNotActive() {
        final FallenBehindMonitor fallenBehindManager = mock(FallenBehindMonitor.class);
        when(fallenBehindManager.hasFallenBehind()).thenReturn(false);

        final SignedState signedState = spy(new RandomSignedStateGenerator().build());
        when(signedState.isComplete()).thenReturn(true);

        final ReservedSignedState reservedSignedState = signedState.reserve("test");

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final Protocol reconnectProtocol = new StateSyncProtocol(
                platformContext,
                getStaticThreadManager(),
                teacherThrottle,
                () -> reservedSignedState,
                Duration.of(100, ChronoUnit.MILLIS),
                reconnectMetrics,
                fallenBehindManager,
                TEST_PLATFORM_STATE_FACADE,
                reservedSignedStatePromise,
                mock(SwirldStateManager.class),
                a -> null);
        reconnectProtocol.updatePlatformStatus(CHECKING);
        final PeerProtocol peerProtocol = reconnectProtocol.createPeerInstance(NodeId.of(0));
        assertFalse(peerProtocol.shouldAccept());
    }

    @Test
    @DisplayName("Teacher holds the learner permit while teaching")
    void teacherHoldsLearnerPermit() {
        final SignedState signedState = spy(new RandomSignedStateGenerator().build());
        when(signedState.isComplete()).thenReturn(true);
        signedState.reserve("test");

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final Protocol reconnectProtocol = new StateSyncProtocol(
                platformContext,
                getStaticThreadManager(),
                teacherThrottle,
                () -> signedState.reserve("test"),
                Duration.of(100, ChronoUnit.MILLIS),
                reconnectMetrics,
                mock(FallenBehindMonitor.class),
                TEST_PLATFORM_STATE_FACADE,
                reservedSignedStatePromise,
                mock(SwirldStateManager.class),
                a -> null);
        reconnectProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = reconnectProtocol.createPeerInstance(NodeId.of(0));
        assertTrue(peerProtocol.shouldAccept());

        verify(reservedSignedStatePromise, times(1)).tryBlock();
        verify(reservedSignedStatePromise, times(0)).release();

        peerProtocol.acceptFailed();

        verify(reservedSignedStatePromise, times(1)).tryBlock();
        verify(reservedSignedStatePromise, times(1)).release();

        assertTrue(peerProtocol.shouldAccept());

        verify(reservedSignedStatePromise, times(2)).tryBlock();
        verify(reservedSignedStatePromise, times(1)).release();

        assertThrows(Exception.class, () -> peerProtocol.runProtocol(mock(Connection.class)));

        verify(reservedSignedStatePromise, times(2)).tryBlock();
        verify(reservedSignedStatePromise, times(2)).release();
    }

    @Test
    @DisplayName("Teacher holds the learner permit while teaching")
    void teacherCantAcquireLearnerPermit() {
        final SignedState signedState = spy(new RandomSignedStateGenerator().build());
        when(signedState.isComplete()).thenReturn(true);
        signedState.reserve("test");

        when(reservedSignedStatePromise.tryBlock()).thenReturn(false);

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final Protocol reconnectProtocol = new StateSyncProtocol(
                platformContext,
                getStaticThreadManager(),
                teacherThrottle,
                () -> signedState.reserve("test"),
                Duration.of(100, ChronoUnit.MILLIS),
                reconnectMetrics,
                mock(FallenBehindMonitor.class),
                TEST_PLATFORM_STATE_FACADE,
                reservedSignedStatePromise,
                mock(SwirldStateManager.class),
                a -> null);
        reconnectProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = reconnectProtocol.createPeerInstance(NodeId.of(0));
        assertFalse(peerProtocol.shouldAccept());

        verify(reservedSignedStatePromise, times(1)).tryBlock();
        verify(reservedSignedStatePromise, times(0)).release();
    }
}
