/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.file.impl;

import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.BLOBS_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.file.FileMetadata;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
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
        this.fileState = requireNonNull(states.get(BLOBS_KEY));
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
