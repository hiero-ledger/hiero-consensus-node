// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static com.hedera.node.app.records.schemas.V0720BlockRecordSchema.WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockrecords.WrappedRecordFileBlockHashes;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;

/**
 * Read-only access to the {@link WrappedRecordFileBlockHashes} queue state.
 */
public class ReadableWrappedRecordFileBlockHashesStore {
    private final ReadableQueueState<WrappedRecordFileBlockHashes> hashesQueue;

    public ReadableWrappedRecordFileBlockHashesStore(@NonNull final ReadableStates states) {
        this.hashesQueue = requireNonNull(states.getQueue(WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_ID));
    }

    public @Nullable WrappedRecordFileBlockHashes peek() {
        return hashesQueue.peek();
    }

    public @NonNull Iterator<WrappedRecordFileBlockHashes> iterator() {
        return hashesQueue.iterator();
    }
}
