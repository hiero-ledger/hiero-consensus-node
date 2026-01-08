// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.swirlds.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides access to the latest immutable state snapshot produced by the block stream.
 * Intended as the dev-mode companion to {@link WorkingStateAccessor} until full TSS proofs are wired in.
 */
@Singleton
public final class BlockProvenStateAccessor implements BlockProvenSnapshotProvider {
    private BlockProvenSnapshot snapshot;

    @Inject
    public BlockProvenStateAccessor() {}

    /**
     * Updates the cached immutable state snapshot.
     *
     * @param state the sealed state snapshot (must be immutable)
     */
    public synchronized void update(@NonNull final MerkleNodeState state) {
        this.snapshot = new BasicSnapshot(state);
    }

    /**
     * Returns the latest sealed state snapshot, if one has been observed.
     *
     * @return optional immutable snapshot
     */
    @Override
    public synchronized Optional<BlockProvenSnapshot> latestSnapshot() {
        return Optional.ofNullable(snapshot);
    }

    /**
     * Returns the latest sealed state snapshot, if one has been observed.
     *
     * @return optional immutable state
     */
    public synchronized Optional<MerkleNodeState> latestState() {
        return Optional.ofNullable(snapshot).map(BlockProvenSnapshot::merkleState);
    }

    private record BasicSnapshot(@NonNull MerkleNodeState merkleState) implements BlockProvenSnapshot {}
}
