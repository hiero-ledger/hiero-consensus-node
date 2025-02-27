// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.file.File;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Files.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public interface ReadableFileStore {

    /**
     * Returns the file metadata needed. If the file doesn't exist returns failureReason. If the
     * file exists , the failure reason will be null.
     *
     * @param id file id being looked up
     * @return file's metadata
     */
    // TODO : Change to return File instead of FileMetadata
    @Nullable
    FileMetadata getFileMetadata(@NonNull FileID id);

    /**
     * Returns the file needed from state, if not exist will return null.
     * @param id file id being looked up
     * @return file if found, null otherwise
     */
    @Nullable
    File getFileLeaf(@NonNull FileID id);

    /**
     * Returns the number of files in the state.
     *
     * @return the number of files in the state
     */
    long sizeOfState();
}
