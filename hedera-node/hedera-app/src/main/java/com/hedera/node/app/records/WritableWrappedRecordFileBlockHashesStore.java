// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static com.hedera.node.app.records.schemas.V0720BlockRecordSchema.WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockrecords.WrappedRecordFileBlockHashes;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Writable access to the {@link WrappedRecordFileBlockHashes} queue state.
 */
public class WritableWrappedRecordFileBlockHashesStore extends ReadableWrappedRecordFileBlockHashesStore {
    private final WritableQueueState<WrappedRecordFileBlockHashes> hashesQueue;

    public WritableWrappedRecordFileBlockHashesStore(@NonNull final WritableStates states) {
        super(states);
        this.hashesQueue = requireNonNull(states.getQueue(WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_ID));
    }

    public void add(@NonNull final WrappedRecordFileBlockHashes hashes) {
        hashesQueue.add(requireNonNull(hashes));
    }

    public @Nullable WrappedRecordFileBlockHashes poll() {
        return hashesQueue.poll();
    }
}
