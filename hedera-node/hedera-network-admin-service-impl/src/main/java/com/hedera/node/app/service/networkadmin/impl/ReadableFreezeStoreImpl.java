// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl;

import static com.hedera.node.app.service.networkadmin.impl.WritableFreezeStore.effectiveUpdateFileHash;
import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.FREEZE_TIME_STATE_ID;
import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.UPGRADE_FILE_HASH_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.networkadmin.ReadableFreezeStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Default implementation of {@link ReadableFreezeStore}.
 */
public class ReadableFreezeStoreImpl implements ReadableFreezeStore {

    /** The underlying data storage classes that hold the freeze state data. */
    private final ReadableSingletonState<Timestamp> freezeTime;

    /** The underlying data storage class that holds the prepared update file hash.
     * May be null if no prepared update file has been set. */
    private final ReadableSingletonState<ProtoBytes> updateFileHash;

    /**
     * Create a new {@link ReadableFreezeStoreImpl} instance.
     * @param states the state to use
     */
    public ReadableFreezeStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.freezeTime = states.getSingleton(FREEZE_TIME_STATE_ID);
        this.updateFileHash = states.getSingleton(UPGRADE_FILE_HASH_STATE_ID);
    }

    @Override
    @Nullable
    public Timestamp freezeTime() {
        return freezeTime.get();
    }

    @Override
    @Nullable
    public Bytes updateFileHash() {
        return effectiveUpdateFileHash(updateFileHash.get());
    }
}
