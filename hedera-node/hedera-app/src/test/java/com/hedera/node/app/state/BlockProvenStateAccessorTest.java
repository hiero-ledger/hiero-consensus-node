// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.swirlds.state.State;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.StateLifecycleManager;
import java.util.Arrays;
import java.util.function.Consumer;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BlockProvenStateAccessorTest {

	private static final Bytes TSS_SIGNATURE_A = Bytes.wrap(new byte[] {1,2,3,4});
	private static final Bytes TSS_SIGNATURE_B = Bytes.wrap(new byte[] {5,6,7,8});
	private static final Bytes BLOCK_HASH_A = Bytes.wrap(new byte[] {10,11,12,13});
	private static final Bytes BLOCK_HASH_B = Bytes.wrap(new byte[] {14,15,16,17});

	private BlockProvenStateAccessor subject;
    private StateLifecycleManager<State, Object> stateLifecycleManager;

    @BeforeEach
    void setUp() {
        // Mockito can only create a raw mock; we cast to the parameterized type for compilation.
        @SuppressWarnings("unchecked")
        final var mocked = (StateLifecycleManager<State, Object>) mock(StateLifecycleManager.class);
        stateLifecycleManager = mocked;
        subject = new BlockProvenStateAccessor(stateLifecycleManager);
    }

	@Test void latestSnapshotEmptyUntilStateAndMetadataAvailable() {
		final var stateHash = newHashBytes(10);
		final var state = mockState(stateHash);

		final var nowSeconds = nowSeconds();
		final var timestamp = Timestamp.newBuilder()
				.seconds(nowSeconds)
				.nanos(123_456_789)
				.build();
		final var merklePath = MerklePath.newBuilder().nextPathIndex(1).build();

		subject.registerBlockMetadata(stateHash, BLOCK_HASH_A, TSS_SIGNATURE_A, timestamp,
				merklePath);
		assertThat(subject.latestSnapshot()).isEmpty();
		assertThat(subject.latestState()).isEmpty();

		subject.observeImmutableState(state);

		final BlockProvenSnapshot snapshot = subject.latestSnapshot().orElseThrow();
		assertThat(snapshot.state()).isSameAs(state);
		assertThat(subject.latestState()).contains(state);
		assertThat(snapshot.tssSignature()).isSameAs(TSS_SIGNATURE_A);
		assertThat(snapshot.blockTimestamp()).isSameAs(timestamp);
		assertThat(snapshot.path()).isSameAs(merklePath);
	}

    @Test
    void latestSnapshotReturnsStateFromLifecycleManager() {
        final var state = mock(State.class);
        when(state.isDestroyed()).thenReturn(false);
        when(state.isMutable()).thenReturn(false);
        when(stateLifecycleManager.getLatestImmutableState()).thenReturn(state);
        final BlockProvenSnapshot snapshot = subject.latestSnapshot().orElseThrow();
        assertThat(snapshot.state()).isSameAs(state);
        assertThat(subject.latestState()).contains(state);
    }

	@Test void updateStoresSnapshot() {
		final var firstStateHash = newHashBytes(11);
		final var firstState = mockState(firstStateHash);
		final var nowSeconds = nowSeconds();
		final var firstTimestamp = Timestamp.newBuilder()
				.seconds(nowSeconds)
				.nanos(123_456_789)
				.build();
		final var firstMerklePath = MerklePath.newBuilder().nextPathIndex(1).build();

		final var secondStateHash = newHashBytes(22);
		final var secondState = mockState(secondStateHash);
		final var secondTimestamp = Timestamp.newBuilder()
				.seconds(nowSeconds +1)
				.nanos(987_654_321)
				.build();
		final var secondMerklePath = MerklePath.newBuilder().nextPathIndex(2).build();

		subject.observeImmutableState(firstState);
		subject.registerBlockMetadata(firstStateHash, BLOCK_HASH_A, TSS_SIGNATURE_A, firstTimestamp, firstMerklePath);

		final BlockProvenSnapshot snapshot = subject.latestSnapshot().orElseThrow();
		assertThat(snapshot.state()).isSameAs(firstState);
		assertThat(subject.latestState()).contains(firstState);
		assertThat(snapshot.tssSignature()).isSameAs(TSS_SIGNATURE_A);
		assertThat(snapshot.blockTimestamp()).isSameAs(firstTimestamp);
		assertThat(snapshot.path()).isSameAs(firstMerklePath);

		subject.registerBlockMetadata(secondStateHash, BLOCK_HASH_B, TSS_SIGNATURE_B, secondTimestamp, secondMerklePath);
		final BlockProvenSnapshot stillFirst = subject.latestSnapshot().orElseThrow();
		assertThat(stillFirst.state()).isSameAs(firstState);

		subject.observeImmutableState(secondState);

		final BlockProvenSnapshot updatedSnapshot = subject.latestSnapshot().orElseThrow();
		assertThat(updatedSnapshot.state()).isSameAs(secondState);
		assertThat(subject.latestState()).contains(secondState);
		assertThat(updatedSnapshot.tssSignature()).isSameAs(TSS_SIGNATURE_B);
		assertThat(updatedSnapshot.blockTimestamp()).isSameAs(secondTimestamp);
		assertThat(updatedSnapshot.path()).isSameAs(secondMerklePath);
	}

	@Test void observeIgnoresDestroyedMutableOrHashlessStates() {
		final var hashlessState = mock(State.class);
		when(hashlessState.getHash()).thenReturn(null);

		final var destroyedState = mockState(newHashBytes(1));
		when(destroyedState.isDestroyed()).thenReturn(true);

		final var mutableState = mockState(newHashBytes(2));
		when(mutableState.isMutable()).thenReturn(true);

		final var timestamp = Timestamp.newBuilder().seconds(nowSeconds()).build();
		final var merklePath = MerklePath.newBuilder().nextPathIndex(3).build();

		subject.observeImmutableState(hashlessState);
		subject.observeImmutableState(destroyedState);
		subject.observeImmutableState(mutableState);

		subject.registerBlockMetadata(newHashBytes(1), BLOCK_HASH_A, TSS_SIGNATURE_A, timestamp, merklePath);
		subject.registerBlockMetadata(newHashBytes(2), BLOCK_HASH_B, TSS_SIGNATURE_B, timestamp, merklePath);

		assertThat(subject.latestSnapshot()).isEmpty();
		assertThat(subject.latestState()).isEmpty();
	}

	@Test void expiredMetadataDoesNotYieldSnapshot() {
		final var stateHash = newHashBytes(30);
		final var state = mockState(stateHash);

		subject.observeImmutableState(state);

		final var expiredTimestamp = Timestamp.newBuilder().seconds(nowSeconds() -10_000L).nanos(0).build();
		final var merklePath = MerklePath.newBuilder().nextPathIndex(4).build();
		subject.registerBlockMetadata(stateHash, BLOCK_HASH_A, TSS_SIGNATURE_A, expiredTimestamp, merklePath);

		assertThat(subject.latestSnapshot()).isEmpty();
		assertThat(subject.latestState()).isEmpty();
	}

	@Test void registeringMetadataBeforeStateDoesNotClearLatestSnapshot() {
		final var firstHash = newHashBytes(40);
		final var firstState = mockState(firstHash);
		final var timestamp = Timestamp.newBuilder().seconds(nowSeconds()).build();
		final var path = MerklePath.newBuilder().nextPathIndex(5).build();

		subject.observeImmutableState(firstState);
		subject.registerBlockMetadata(firstHash, BLOCK_HASH_A, TSS_SIGNATURE_A, timestamp, path);

		final var secondHash = newHashBytes(41);
		subject.registerBlockMetadata(secondHash, BLOCK_HASH_B, TSS_SIGNATURE_B, timestamp, path);

		final BlockProvenSnapshot snapshot = subject.latestSnapshot().orElseThrow();
		assertThat(snapshot.state()).isSameAs(firstState);
		assertThat(snapshot.tssSignature()).isSameAs(TSS_SIGNATURE_A);
	}

	@Test void constructorRegistersObserverAndObserverUpdatesState() {
		final var captor = ArgumentCaptor.forClass(Consumer.class);
		verify(stateLifecycleManager).addObserver(captor.capture());

		final var stateHash = newHashBytes(50);
		final var state = mockState(stateHash);
		final var timestamp = Timestamp.newBuilder().seconds(nowSeconds()).build();
		final var path = MerklePath.newBuilder().nextPathIndex(6).build();

		subject.registerBlockMetadata(stateHash, BLOCK_HASH_A, TSS_SIGNATURE_A, timestamp, path);
		@SuppressWarnings("unchecked")
		final Consumer<State> observer = captor.getValue();
		observer.accept(state);

		assertThat(subject.latestSnapshot()).isPresent();
		assertThat(subject.latestState()).contains(state);
	}

	private static long nowSeconds() {
		return System.currentTimeMillis() /1000L;
	}

	private static Bytes newHashBytes(final int seed) {
		final var bytes = new byte[DigestType.SHA_384.digestLength()];
		Arrays.fill(bytes, (byte) seed);
		return Bytes.wrap(bytes);
	}

	private static State mockState(final Bytes stateHash) {
		final var state = mock(State.class);
		final var hash = mock(Hash.class);
		when(hash.getBytes()).thenReturn(stateHash);
		when(state.getHash()).thenReturn(hash);
		when(state.isDestroyed()).thenReturn(false);
		when(state.isMutable()).thenReturn(false);
		return state;
	}
}
