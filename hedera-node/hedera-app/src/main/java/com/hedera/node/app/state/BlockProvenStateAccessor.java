// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.StateLifecycleManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Singleton;

/**
 * Provides access to the latest immutable state snapshot from the StateLifecycleManager.
 * Intended as the dev-mode companion to {@link WorkingStateAccessor} until full TSS proofs are wired in.
 */
@Singleton
public final class BlockProvenStateAccessor implements BlockProvenSnapshotProvider {
    private final StateLifecycleManager stateLifecycleManager;

    public BlockProvenStateAccessor(@NonNull final StateLifecycleManager stateLifecycleManager) {
        this.stateLifecycleManager = Objects.requireNonNull(stateLifecycleManager);
    }

    /**
     * Returns the latest sealed state snapshot, if one has been observed.
     *
     * @return optional immutable snapshot
     */
    @Override
    public synchronized Optional<BlockProvenSnapshot> latestSnapshot() {
        try {
            final var state = stateLifecycleManager.getLatestImmutableState();
            if (state.isDestroyed() || state.isMutable()) {
                return Optional.empty();
            }
            return Optional.of(new BasicSnapshot(state));
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns the latest sealed state snapshot, if one has been observed.
     *
     * @return optional immutable state
     */
    public synchronized Optional<MerkleNodeState> latestState() {
        return latestSnapshot().map(BlockProvenSnapshot::merkleState);
    }

    private record BasicSnapshot(@NonNull MerkleNodeState merkleState) implements BlockProvenSnapshot {}
}
