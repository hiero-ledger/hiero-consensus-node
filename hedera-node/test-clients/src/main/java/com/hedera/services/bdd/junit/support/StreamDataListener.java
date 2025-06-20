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
     * Called when a new block is received.
     * @param block the new block
     */
    default void onNewBlock(@NonNull final Block block) {}

    default void onNewItem(RecordStreamItem item) {}

    default void onNewSidecar(TransactionSidecarRecord sidecar) {}

    default String name() {
        return "AnonymousListener";
    }
}
