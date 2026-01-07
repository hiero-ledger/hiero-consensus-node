// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
     * Updates all block-related data needed to produce a state proof, e.g. the cached immutable state snapshot
     *
     * @param state the sealed state snapshot (must be immutable)
     * @param tssSignature the TSS signature for the block
     * @param blockTimestamp the timestamp of the block
     * @param path the <i>partial</i> Merkle path from the state's subroot to the block root
     */
    public synchronized void update(
            @NonNull final MerkleNodeState state,
            @NonNull final Bytes tssSignature,
            @NonNull final Timestamp blockTimestamp,
            @NonNull final MerklePath path) {
        this.snapshot = new BlockSignedSnapshot(state, tssSignature, blockTimestamp, path);
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

    private record BlockSignedSnapshot(
            @NonNull MerkleNodeState merkleState,
            @NonNull Bytes tssSignature,
            @NonNull Timestamp blockTimestamp,
            @NonNull MerklePath path)
            implements BlockProvenSnapshot {}
}
