// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.model;

import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Pre-parsed hash record from in-memory storage for validation processing.
 *
 * <p>Unlike {@link DiskDataItem} which carries serialized bytes from disk, this record
 * holds an already-parsed {@link VirtualHashRecord} from the in-memory hash store,
 * avoiding unnecessary serialization/deserialization overhead.
 *
 * <p>Items from memory are always considered "live" - no liveness check against
 * disk location indexes is needed.
 *
 * @param hashRecord the pre-parsed virtual hash record containing path and hash
 */
public record MemoryHashItem(@NonNull VirtualHashRecord hashRecord) implements ValidationItem {

    @Override
    public boolean isPoisonPill() {
        return false;
    }
}
