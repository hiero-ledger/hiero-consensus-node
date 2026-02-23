// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl;

import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.ReadableEntityCounters;
import com.hedera.node.app.service.file.FileMetadata;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Files.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public class ReadableFileStoreImpl extends FileStore implements ReadableFileStore {
    /** The underlying data storage class that holds the file data. */
    private final ReadableKVState<FileID, File> fileState;

    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableFileStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableFileStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        this.fileState = requireNonNull(states.get(FILES_STATE_ID));
        this.entityCounters = requireNonNull(entityCounters);
    }

    /**
     * Returns the file metadata needed. If the file doesn't exist returns failureReason. If the
     * file exists , the failure reason will be null.
     *
     * @param id file id being looked up
     * @return file's metadata
     */
    public @Nullable FileMetadata getFileMetadata(@NonNull final FileID id) {
        final var file = getFileLeaf(id);
        return file == null ? null : FileStore.fileMetaFrom(file);
    }

    /**
     * Returns the file leaf for the given file id.
     *
     * @param id the file id
     * @return the file for the given file id
     */
    public @Nullable File getFileLeaf(@NonNull FileID id) {
        return fileState.get(id);
    }

    /**
     * Returns the number of files in the state.
     *
     * @return the number of files in the state
     */
    public long sizeOfState() {
        return entityCounters.getCounterFor(EntityType.FILE);
    }
}
