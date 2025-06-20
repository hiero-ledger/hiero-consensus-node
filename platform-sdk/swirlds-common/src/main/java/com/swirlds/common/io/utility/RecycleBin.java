// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.utility;

import com.swirlds.base.state.Startable;
import com.swirlds.base.state.Stoppable;
import com.swirlds.base.time.Time;
import com.swirlds.common.io.config.FileSystemManagerConfig;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.consensus.model.node.NodeId;

/**
 * This class provides the abstraction of deleting a file, but actually moves the file to a temporary location in case
 * the file becomes useful later for debugging.
 * <p>
 * Data moved to the recycle bin persist in the temporary location for an unspecified amount of time, perhaps even no
 * time at all. Files in this temporary location may be deleted at any time without warning. It is never ok to write
 * code that depends on the existence of files in this temporary location. Files in this temporary location should be
 * treated as deleted by java code, and only used for debugging purposes.
 */
public interface RecycleBin extends Startable, Stoppable {

    /**
     * Remove a file or directory tree from its current location and move it to a temporary location.
     * <p>
     * Recycled data will persist in the temporary location for an unspecified amount of time, perhaps even no time at
     * all. Files in this temporary location may be deleted at any time without warning. It is never ok to write code
     * that depends on the existence of files in this temporary location. Files in this temporary location should be
     * treated as deleted by java code, and only used for debugging purposes.
     *
     * @param path the file or directory to recycle
     */
    void recycle(@NonNull Path path) throws IOException;

    /**
     * Create a default recycle bin.
     *
     * @param metrics           manages the creation of metrics
     * @param configuration     configuration
     * @param threadManager     manages the creation of threads
     * @param time              provides wall clock time
     * @param fileSystemManager the manager that would be used to operate the fs.
     * @param nodeId            this node id
     */
    static RecycleBin create(
            @NonNull final Metrics metrics,
            @NonNull final Configuration configuration,
            @NonNull final ThreadManager threadManager,
            @NonNull final Time time,
            @NonNull final FileSystemManager fileSystemManager,
            @NonNull final NodeId nodeId) {
        final FileSystemManagerConfig fsmConfig = configuration.getConfigData(FileSystemManagerConfig.class);
        final Path recycleBinPath =
                fileSystemManager.resolve(Path.of(fsmConfig.recycleBinDir())).resolve(nodeId.toString());

        return new RecycleBinImpl(
                metrics,
                threadManager,
                time,
                recycleBinPath,
                fsmConfig.recycleBinMaximumFileAge(),
                fsmConfig.recycleBinCollectionPeriod());
    }
}
