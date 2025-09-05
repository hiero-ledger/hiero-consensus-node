// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl;

import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.FREEZE_TIME_STATE_ID;
import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.UPGRADE_FILE_HASH_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides write methods for modifying underlying data storage mechanisms for
 * working with freeze states.
 */
public class WritableFreezeStore extends ReadableFreezeStoreImpl {

    /** The underlying data storage classes that hold the freeze state data. */
    private final WritableSingletonState<Timestamp> freezeTimeState;

    /** The underlying data storage class that holds the update file hash. */
    private final WritableSingletonState<ProtoBytes> updateFileHash;

    /**
     * Create a new {@link WritableFreezeStore} instance.
     *
     * @param states The state to use.
     */
    public WritableFreezeStore(@NonNull final WritableStates states) {
        super(states);
        requireNonNull(states);
        freezeTimeState = states.getSingleton(FREEZE_TIME_STATE_ID);
        updateFileHash = states.getSingleton(UPGRADE_FILE_HASH_STATE_ID);
    }

    /**
     * Sets the freeze time.
     *
     * @param freezeTime the freeze time to set; if null, clears the freeze time
     */
    public void freezeTime(@NonNull final Timestamp freezeTime) {
        freezeTimeState.put(freezeTime);
    }

    /**
     * Gets the scheduled freeze time. If no freeze has been scheduled, returns null.
     */
    @Override
    @Nullable
    public Timestamp freezeTime() {
        return Timestamp.DEFAULT.equals(freezeTimeState.get()) ? null : freezeTimeState.get();
    }

    /**
     * Sets or clears the update file hash.
     *
     * @param updateFileHashBytes The update file hash to set. If null, clears the update file hash.
     */
    public void updateFileHash(@NonNull final Bytes updateFileHashBytes) {
        requireNonNull(updateFileHashBytes);
        this.updateFileHash.put(new ProtoBytes(updateFileHashBytes));
    }

    @Override
    @Nullable
    public Bytes updateFileHash() {
        return effectiveUpdateFileHash(updateFileHash.get());
    }

    static @Nullable Bytes effectiveUpdateFileHash(@Nullable final ProtoBytes fileHash) {
        if (fileHash == null) {
            return null;
        }
        return Bytes.EMPTY.equals(fileHash.value()) ? null : fileHash.value();
    }
}
