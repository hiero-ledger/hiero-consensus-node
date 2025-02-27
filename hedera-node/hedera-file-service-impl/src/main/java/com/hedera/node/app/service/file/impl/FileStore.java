// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.service.file.FileMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Base class for {@link ReadableFileStoreImpl} and {@link WritableFileStore}.
 */
public abstract class FileStore {

    protected FileStore() {
        // We have only static methods here. There is no need to initialize the class
    }

    /**
     * Convert a {@link File} to a {@link FileMetadata}.
     *
     * @param file The {@link File} to convert
     * @return The {@link FileMetadata} representation of the {@link File}
     */
    protected static @NonNull FileMetadata fileMetaFrom(@NonNull final File file) {
        Objects.requireNonNull(file);
        return new FileMetadata(
                file.fileId(),
                Timestamp.newBuilder().seconds(file.expirationSecond()).build(),
                file.keys(),
                file.contents(),
                file.memo(),
                file.deleted(),
                Timestamp.newBuilder()
                        .seconds(file.preSystemDeleteExpirationSecond())
                        .build());
    }
}
