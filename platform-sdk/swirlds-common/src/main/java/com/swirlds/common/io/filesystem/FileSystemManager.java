// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.filesystem;

import com.swirlds.common.io.config.FileSystemManagerConfig;
import com.swirlds.common.io.filesystem.internal.FileSystemManagerImpl;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Responsible for organizing and managing access to the file system.
 */
public interface FileSystemManager {

    /**
     * Creates a {@link FileSystemManager} by searching {@code root}
     * path in the {@link Configuration} class using
     * {@code FileSystemManagerConfig} record
     *
     * @param configuration the configuration instance to retrieve properties from
     * @return a new instance of {@link FileSystemManager}
     * @throws UncheckedIOException if the dir structure to rootLocation cannot be created
     */
    @NonNull
    static FileSystemManager create(@NonNull final Configuration configuration) {
        final FileSystemManagerConfig fsmConfig = configuration.getConfigData(FileSystemManagerConfig.class);
        return new FileSystemManagerImpl(fsmConfig.rootPath(), fsmConfig.userDataDir(), fsmConfig.tmpDir());
    }

    /**
     * Resolve a path relative to the root directory of the file system manager.
     * Implementations can choose the convenient subfolder inside the root directory.
     *
     * @param relativePath the path to resolve against the root directory
     * @return the resolved path
     * @throws IllegalArgumentException if the path is "above" the root directory (e.g. resolve("../foo")
     */
    @NonNull
    Path resolve(@NonNull Path relativePath);

    /**
     * Creates a path relative to the root directory of the file system manager.
     * Implementations can choose the convenient subfolder inside the root directory.
     * There is no file or directory actually being created after the invocation of this method.
     *
     * @param tag if indicated, will be suffixed to the returned path
     * @return the resolved path
     * @throws IllegalArgumentException if the path is "above" the root directory (e.g. resolve("../foo")
     */
    @NonNull
    Path resolveNewTemp(@Nullable String tag);
}
