// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A listener that receives record stream items and transaction sidecar records.
 */
public interface StreamDataListener {
    /**
     * Returns true if this listener should receive a replay of existing files.
     */
    default boolean replayExistingFiles() {
        return false;
    }

    /**
     * Returns true if this listener reads state change values, and so needs any partial updates in a block
     * expanded to full values. Proofs must not be checked against such a block's bytes.
     */
    default boolean wantsFullStateValues() {
        return false;
    }

    /**
     * Called when a new block is received.
     * @param block the new block
     */
    default void onNewBlock(@NonNull final Block block) {}

    /** Called instead of onNewBlock when full state values cannot be reconstructed. */
    default void onBlockError(@NonNull final Throwable error) {
        throw new AssertionError("Cannot reconstruct full block state values", error);
    }

    default void onNewItem(RecordStreamItem item) {}

    default void onNewSidecar(TransactionSidecarRecord sidecar) {}

    default String name() {
        return "AnonymousListener";
    }
}
