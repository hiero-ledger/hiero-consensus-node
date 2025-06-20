// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;

/**
 * This class allows accessing and modifying the content of the node's fileSystem
 */
public interface NodeFileSystem {

    /**
     * Retrieves the location of the latest snapshot state folder
     * @return the location of the latest snapshot state folder
     */
    @NonNull
    Path getLatestSnapshotStateDir();

    /**
     * Takes a snapshot folder and moves the files to the right location so that the node can start using that state
     * @param snapshotLocation the location of the snapshot to copy
     */
    void useSnapshot(@NonNull Path snapshotLocation);
}
