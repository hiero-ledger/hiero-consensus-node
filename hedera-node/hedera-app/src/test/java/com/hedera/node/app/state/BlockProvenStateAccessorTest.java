// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.virtualmap.VirtualMap;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.platformstate.V0540PlatformStateSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockProvenStateAccessorTest {
    private static final long ROUND_NO = 123L;
    private static final Hash STATE_HASH = new Hash(new byte[48]);
    private static final Hash OTHER_STATE_HASH = new Hash(bytesOf((byte) 1));
    private static final Bytes BLOCK_HASH = Bytes.fromHex("ab".repeat(48));
    private static final Bytes TSS_SIGNATURE = Bytes.fromHex("cd".repeat(48));
    private static final Timestamp BLOCK_TIMESTAMP = new Timestamp(1_234_567L, 890);

    @Mock
    private StateLifecycleManager<VirtualMapState, Object> stateLifecycleManager;

    @Mock
    private VirtualMapState state;

    @Mock
    private VirtualMap root;

    private BlockProvenStateAccessor subject;

    @BeforeEach
    void setUp() {
        subject = new BlockProvenStateAccessor(stateLifecycleManager);
    }

    @Test
    void reservesCachedStateAndHandsOutClosableSnapshotWithItsOwnReservation() {
        given(stateLifecycleManager.getLatestImmutableState()).willReturn(state);
        given(state.getHash()).willReturn(STATE_HASH);
        given(state.getRoot()).willReturn(root);
        given(root.tryReserve()).willReturn(true);

        subject.notify(new StateHashedNotification(ROUND_NO, STATE_HASH));
        // The cache entry took its own reservation
        verify(root).tryReserve();

        subject.registerBlockMetadata(
                STATE_HASH.getBytes(), BLOCK_HASH, TSS_SIGNATURE, BLOCK_TIMESTAMP, MerklePath.DEFAULT);

        final var maybeSnapshot = subject.latestSnapshot();
        assertTrue(maybeSnapshot.isPresent());
        final var snapshot = maybeSnapshot.get();
        // The handed-out snapshot took a second reservation on behalf of the caller
        verify(root, times(2)).tryReserve();
        assertSame(state, snapshot.state());
        assertEquals(TSS_SIGNATURE, snapshot.tssSignature());
        assertEquals(BLOCK_TIMESTAMP, snapshot.blockTimestamp());
        assertEquals(MerklePath.DEFAULT, snapshot.path());

        // Closing the snapshot releases exactly one reservation, even if closed twice
        snapshot.close();
        snapshot.close();
        verify(state, times(1)).release();
    }

    @Test
    void doesNotCacheStateWhenReservationFails() {
        given(stateLifecycleManager.getLatestImmutableState()).willReturn(state);
        given(state.getHash()).willReturn(STATE_HASH);
        given(state.getRoot()).willReturn(root);
        given(root.tryReserve()).willReturn(false);

        subject.notify(new StateHashedNotification(ROUND_NO, STATE_HASH));
        subject.registerBlockMetadata(
                STATE_HASH.getBytes(), BLOCK_HASH, TSS_SIGNATURE, BLOCK_TIMESTAMP, MerklePath.DEFAULT);

        assertTrue(subject.latestSnapshot().isEmpty());
    }

    @Test
    void cachesLatestStateUnderItsOwnHashEvenWhenNotificationIsForAnOlderRound() {
        given(stateLifecycleManager.getLatestImmutableState()).willReturn(state);
        given(state.getHash()).willReturn(STATE_HASH);
        given(state.getRoot()).willReturn(root);
        given(root.tryReserve()).willReturn(true);

        // The notified round's hash differs from the latest immutable state's; the state is
        // still cached under its own hash, since metadata is paired strictly by state hash
        subject.notify(new StateHashedNotification(ROUND_NO, OTHER_STATE_HASH));
        subject.registerBlockMetadata(
                STATE_HASH.getBytes(), BLOCK_HASH, TSS_SIGNATURE, BLOCK_TIMESTAMP, MerklePath.DEFAULT);

        assertTrue(subject.latestSnapshot().isPresent());
    }

    @Test
    void notCompletableUntilBothStateAndMetadataAreObserved() {
        given(stateLifecycleManager.getLatestImmutableState()).willReturn(state);
        given(state.getHash()).willReturn(STATE_HASH);
        given(state.getRoot()).willReturn(root);
        given(root.tryReserve()).willReturn(true);

        subject.notify(new StateHashedNotification(ROUND_NO, STATE_HASH));
        // Metadata is for a different state hash, so no completable snapshot exists
        subject.registerBlockMetadata(
                OTHER_STATE_HASH.getBytes(), BLOCK_HASH, TSS_SIGNATURE, BLOCK_TIMESTAMP, MerklePath.DEFAULT);

        assertTrue(subject.latestSnapshot().isEmpty());
    }

    @Test
    void returnsEmptyWhenLatestImmutableStateIsNotYetHashed() {
        given(stateLifecycleManager.getLatestImmutableState()).willReturn(state);
        given(state.getHash()).willReturn(null);

        subject.notify(new StateHashedNotification(ROUND_NO, STATE_HASH));
        subject.registerBlockMetadata(
                STATE_HASH.getBytes(), BLOCK_HASH, TSS_SIGNATURE, BLOCK_TIMESTAMP, MerklePath.DEFAULT);

        assertTrue(subject.latestSnapshot().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsEmptyForNonMerkleState() {
        final StateLifecycleManager<State, Object> nonMerkleManager =
                org.mockito.Mockito.mock(StateLifecycleManager.class);
        final var nonMerkleState = org.mockito.Mockito.mock(State.class);
        given(nonMerkleManager.getLatestImmutableState()).willReturn(nonMerkleState);
        given(nonMerkleState.getHash()).willReturn(STATE_HASH);

        final var accessor = new BlockProvenStateAccessor(nonMerkleManager);
        accessor.notify(new StateHashedNotification(ROUND_NO, STATE_HASH));
        accessor.registerBlockMetadata(
                STATE_HASH.getBytes(), BLOCK_HASH, TSS_SIGNATURE, BLOCK_TIMESTAMP, MerklePath.DEFAULT);

        assertTrue(accessor.latestSnapshot().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void drainsCacheWhenNotifyFiresForFreezeRound() {
        given(stateLifecycleManager.getLatestImmutableState()).willReturn(state);
        given(state.getHash()).willReturn(STATE_HASH);
        given(state.getRoot()).willReturn(root);
        given(root.tryReserve()).willReturn(true);
        // Declare ROUND_NO as the freeze round via the platform state singleton chain.
        final var readableStates = org.mockito.Mockito.mock(ReadableStates.class);
        final var singleton = org.mockito.Mockito.mock(ReadableSingletonState.class);
        given(state.getReadableStates(PlatformStateService.NAME)).willReturn(readableStates);
        given(readableStates.<PlatformState>getSingleton(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID))
                .willReturn(singleton);
        given(singleton.get())
                .willReturn(
                        PlatformState.newBuilder().latestFreezeRound(ROUND_NO).build());

        subject.notify(new StateHashedNotification(ROUND_NO, STATE_HASH));

        // Cache took its reservation, then the freeze-round detection drained it: state.release()
        // is invoked exactly once on the cached entry.
        verify(root).tryReserve();
        verify(state, times(1)).release();
        // Metadata arriving after the drain finds an empty state cache, so no snapshot is ever
        // pairable for this hash.
        subject.registerBlockMetadata(
                STATE_HASH.getBytes(), BLOCK_HASH, TSS_SIGNATURE, BLOCK_TIMESTAMP, MerklePath.DEFAULT);
        assertTrue(subject.latestSnapshot().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotDrainCacheWhenNotifyFiresForNonFreezeRound() {
        given(stateLifecycleManager.getLatestImmutableState()).willReturn(state);
        given(state.getHash()).willReturn(STATE_HASH);
        given(state.getRoot()).willReturn(root);
        given(root.tryReserve()).willReturn(true);
        // Freeze round is set but doesn't match the notification round, so no drain.
        final var readableStates = org.mockito.Mockito.mock(ReadableStates.class);
        final var singleton = org.mockito.Mockito.mock(ReadableSingletonState.class);
        given(state.getReadableStates(PlatformStateService.NAME)).willReturn(readableStates);
        given(readableStates.<PlatformState>getSingleton(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID))
                .willReturn(singleton);
        given(singleton.get())
                .willReturn(PlatformState.newBuilder()
                        .latestFreezeRound(ROUND_NO + 1)
                        .build());

        subject.notify(new StateHashedNotification(ROUND_NO, STATE_HASH));
        subject.registerBlockMetadata(
                STATE_HASH.getBytes(), BLOCK_HASH, TSS_SIGNATURE, BLOCK_TIMESTAMP, MerklePath.DEFAULT);

        assertTrue(subject.latestSnapshot().isPresent());
    }

    private static byte[] bytesOf(final byte value) {
        final var bytes = new byte[48];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
