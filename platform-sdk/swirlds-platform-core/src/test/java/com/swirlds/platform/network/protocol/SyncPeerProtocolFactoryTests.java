// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.BEHIND;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.SyncException;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.gossip.sync.protocol.SyncPeerProtocol;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkProtocolException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for {@link SyncPeerProtocol}
 */
@DisplayName("Sync Protocol Tests")
class SyncPeerProtocolFactoryTests {
    private NodeId peerId;
    private ShadowgraphSynchronizer shadowGraphSynchronizer;
    private FallenBehindManager fallenBehindManager;
    private Duration sleepAfterSync;
    private SyncMetrics syncMetrics;
    private FakeTime time;
    private PlatformContext platformContext;
    private int ROSTER_SIZE = 3;

    /**
     * Counts the number of currently available sync permits in the permit provider.
     *
     * @param permitProvider the permit provider to measure
     * @return the number of available permits
     */
    private static int countAvailablePermits(@NonNull final SyncPermitProvider permitProvider) {
        int count = 0;
        while (permitProvider.acquire()) {
            count++;
        }
        for (int i = 0; i < count; i++) {
            permitProvider.release();
        }
        return count;
    }

    @BeforeEach
    void setup() {
        peerId = NodeId.of(1);
        shadowGraphSynchronizer = mock(ShadowgraphSynchronizer.class);
        fallenBehindManager = mock(FallenBehindManager.class);

        time = new FakeTime();
        platformContext = TestPlatformContextBuilder.create().withTime(time).build();

        sleepAfterSync = Duration.ofMillis(0);
        syncMetrics = mock(SyncMetrics.class);

        // Set reasonable defaults. Special cases to be configured in individual tests

        // node is not fallen behind
        Mockito.when(fallenBehindManager.hasFallenBehind()).thenReturn(false);
    }

    @Test
    @DisplayName("Protocol should initiate connection")
    void shouldInitiate() {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertTrue(syncProtocol.createPeerInstance(peerId).shouldInitiate());
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol won't initiate connection if cooldown isn't complete")
    void initiateCooldown() {

        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                Duration.ofMillis(100),
                syncMetrics,
                ROSTER_SIZE);
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);
        // do an initial sync, so we can verify that the resulting cooldown period is respected
        assertTrue(peerProtocol.shouldInitiate());
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertDoesNotThrow(() -> peerProtocol.runProtocol(mock(Connection.class)));
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));

        // no time has passed since the previous protocol
        assertFalse(peerProtocol.shouldInitiate());
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));

        // tick part of the way through the cooldown period
        time.tick(Duration.ofMillis(55));

        assertFalse(peerProtocol.shouldInitiate());
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));

        // tick past the end of the cooldown period
        time.tick(Duration.ofMillis(55));

        assertTrue(peerProtocol.shouldInitiate());
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol doesn't initiate if platform has the wrong status")
    void incorrectStatusToInitiate() {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(BEHIND);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertFalse(peerProtocol.shouldInitiate());
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol doesn't initiate without a permit")
    void noPermitAvailableToInitiate() {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(BEHIND);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        // obtain the only existing permits, so none are available to the protocol
        syncProtocol.getPermitProvider().acquire();
        syncProtocol.getPermitProvider().acquire();
        assertEquals(0, countAvailablePermits(syncProtocol.getPermitProvider()));

        assertFalse(peerProtocol.shouldInitiate());
        assertEquals(0, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol doesn't initiate if peer agnostic checks fail")
    void peerAgnosticChecksFailAtInitiate() {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.start();
        syncProtocol.updatePlatformStatus(ACTIVE);
        syncProtocol.pause();
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertFalse(peerProtocol.shouldInitiate());
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol doesn't initiate if it has fallen behind")
    void fallenBehindAtInitiate() {
        // node is fallen behind
        Mockito.when(fallenBehindManager.hasFallenBehind()).thenReturn(true);

        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertFalse(peerProtocol.shouldInitiate());
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol initiates if peer is needed for fallen behind")
    void initiateForFallenBehind() {

        // peer *is* needed for fallen behind (by default)
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertTrue(peerProtocol.shouldInitiate());
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol initiates if peer is part of critical quorum")
    void initiateForCriticalQuorum() {
        // peer 6 isn't needed for fallen behind, but it *is* in critical quorum (by default)
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(NodeId.of(6));

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertTrue(peerProtocol.shouldInitiate());
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol should accept connection")
    void shouldAccept() {

        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        // obtain 1 of the permits, but 1 will still be available to accept
        syncProtocol.getPermitProvider().acquire();
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));

        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertTrue(peerProtocol.shouldAccept());
        assertEquals(0, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol won't accept connection if cooldown isn't complete")
    void acceptCooldown() {

        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                Duration.ofMillis(100),
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        // do an initial sync, so we can verify that the resulting cooldown period is respected
        assertTrue(peerProtocol.shouldAccept());
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertDoesNotThrow(() -> peerProtocol.runProtocol(mock(Connection.class)));
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));

        // no time has passed since the previous protocol
        assertFalse(peerProtocol.shouldAccept());
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));

        // tick part of the way through the cooldown period
        time.tick(Duration.ofMillis(55));

        assertFalse(peerProtocol.shouldAccept());
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));

        // tick past the end of the cooldown period
        time.tick(Duration.ofMillis(55));

        assertTrue(peerProtocol.shouldAccept());
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol doesn't accept if platform has the wrong status")
    void incorrectStatusToAccept() {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(BEHIND);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertFalse(peerProtocol.shouldAccept());
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol doesn't accept without a permit")
    void noPermitAvailableToAccept() {

        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));

        // waste both available permits
        syncProtocol.getPermitProvider().acquire();
        syncProtocol.getPermitProvider().acquire();

        assertEquals(0, countAvailablePermits(syncProtocol.getPermitProvider()));

        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertFalse(peerProtocol.shouldAccept());
        assertEquals(0, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol doesn't accept if peer agnostic checks fail")
    void peerAgnosticChecksFailAtAccept() {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.start();
        syncProtocol.updatePlatformStatus(ACTIVE);
        syncProtocol.pause();
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertFalse(peerProtocol.shouldAccept());
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol doesn't accept if it has fallen behind")
    void fallenBehindAtAccept() {
        // node is fallen behind
        Mockito.when(fallenBehindManager.hasFallenBehind()).thenReturn(true);

        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertFalse(peerProtocol.shouldAccept());
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Permit closes after failed accept")
    void permitClosesAfterFailedAccept() {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertTrue(peerProtocol.shouldAccept());
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));
        peerProtocol.acceptFailed();
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Permit closes after failed initiate")
    void permitClosesAfterFailedInitiate() {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertTrue(peerProtocol.shouldInitiate());
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));
        peerProtocol.initiateFailed();
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol runs successfully when initiating")
    void successfulInitiatedProtocol() {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        peerProtocol.shouldInitiate();
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertDoesNotThrow(() -> peerProtocol.runProtocol(mock(Connection.class)));
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Protocol runs successfully when accepting")
    void successfulAcceptedProtocol() {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        peerProtocol.shouldAccept();
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));
        assertDoesNotThrow(() -> peerProtocol.runProtocol(mock(Connection.class)));
        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("ParallelExecutionException is caught and rethrown as NetworkProtocolException")
    void rethrowParallelExecutionException()
            throws ParallelExecutionException, IOException, SyncException, InterruptedException {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        // mock synchronize to throw a ParallelExecutionException
        Mockito.when(shadowGraphSynchronizer.synchronize(any(), any(), anyBoolean()))
                .thenThrow(new ParallelExecutionException(mock(Throwable.class)));

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        peerProtocol.shouldAccept();
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));

        assertThrows(NetworkProtocolException.class, () -> peerProtocol.runProtocol(mock(Connection.class)));

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("Exception with IOException as root cause is caught and rethrown as IOException")
    void rethrowRootCauseIOException()
            throws ParallelExecutionException, IOException, SyncException, InterruptedException {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        // mock synchronize to throw a ParallelExecutionException with root cause being an IOException
        Mockito.when(shadowGraphSynchronizer.synchronize(any(), any(), anyBoolean()))
                .thenThrow(new ParallelExecutionException(new IOException()));

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        peerProtocol.shouldAccept();
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));

        assertThrows(IOException.class, () -> peerProtocol.runProtocol(mock(Connection.class)));

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("SyncException is caught and rethrown as NetworkProtocolException")
    void rethrowSyncException() throws ParallelExecutionException, IOException, SyncException, InterruptedException {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        // mock synchronize to throw a SyncException
        Mockito.when(shadowGraphSynchronizer.synchronize(any(), any(), anyBoolean()))
                .thenThrow(new SyncException(""));

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
        peerProtocol.shouldAccept();
        assertEquals(1, countAvailablePermits(syncProtocol.getPermitProvider()));

        assertThrows(NetworkProtocolException.class, () -> peerProtocol.runProtocol(mock(Connection.class)));

        assertEquals(2, countAvailablePermits(syncProtocol.getPermitProvider()));
    }

    @Test
    @DisplayName("acceptOnSimultaneousInitiate should return true")
    void acceptOnSimultaneousInitiate() {
        final SyncProtocol syncProtocol = new SyncProtocol(
                platformContext,
                shadowGraphSynchronizer,
                fallenBehindManager,
                mock(IntakeEventCounter.class),
                sleepAfterSync,
                syncMetrics,
                ROSTER_SIZE);
        syncProtocol.updatePlatformStatus(ACTIVE);
        final PeerProtocol peerProtocol = syncProtocol.createPeerInstance(peerId);

        assertTrue(peerProtocol.acceptOnSimultaneousInitiate());
    }
}
