// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.state;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents an immutable snapshot of application state that has been sealed at the end of a block.
 *
 * <p>A snapshot may hold a reservation on the underlying merkle state to keep it from being
 * destroyed while in use; callers must {@link #close()} the snapshot when done with it (ideally
 * via try-with-resources) so the reservation is released.</p>
 *
 * <p>Future iterations may expose additional metadata (e.g., block proofs, consensus timestamps) as that
 * information becomes available to services.</p>
 */
public interface BlockProvenSnapshot extends AutoCloseable {
    /**
     * Returns the immutable state captured at the block boundary.
     *
     * @return the sealed {@link State}
     */
    @NonNull
    State state();

    /**
     * Returns the TSS signature for the block corresponding to this state snapshot.
     */
    @NonNull
    Bytes tssSignature();

    /**
     * Returns this ledger's id (the genesis-set root of trust for TSS verification),
     * sourced from {@code ReadableHistoryStore.getLedgerId()}. Empty bytes if not yet
     * established (e.g. during very early bring-up).
     */
    @NonNull
    Bytes ledgerId();

    /**
     * Returns the consensus timestamp of the block corresponding to this state snapshot.
     */
    @NonNull
    Timestamp blockTimestamp();

    /**
     * Returns the partial Merkle path from the block's starting-state subroot up to the
     * block's root hash, as computed by {@code PartialPathBuilder.startingStateToBlockRoot}.
     * Appending this path's siblings to a state-leaf's proof produces a complete
     * leaf-to-block-root proof.
     */
    @NonNull
    MerklePath path();

    /**
     * Releases any reservation this snapshot holds on the underlying state. Idempotent.
     * Narrowed from {@link AutoCloseable#close()} to throw no checked exception.
     */
    @Override
    default void close() {
        // No-op by default; implementations holding a state reservation override this.
    }
}
