// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Interface for classes that can be snapshotted.
 * <b>
 * Only one snapshot can happen at a time!
 * </b>
 * <b>
 * IMPORTANT, after this is completed the caller owns the directory. It is responsible for deleting it when it
 * is no longer needed.
 * </b>
 */
public interface Snapshotable {

    /**
     * Perform a snapshot, saving data contained in this object to the specified directory.
     *
     * Snapshots are done in the transaction handler thread, so they have to be synchronized with flushes
     * (running in virtual pipeline thread) and compactions (running in dedicated background threads). To sync
     * up with flushes, this method is run either from VirtualRootNode.detach(), which is called while pipeline
     * is paused, or on learner reconnect, while no data is stored to the virtual map. Synchronization with
     * compaction processes is slightly more complicated. Before virtual map snapshot is started, it takes a
     * lock (semaphore), which is monitored by compaction threads. As soon as the semaphore is locked,
     * compaction is immediately stopped. If compaction thread is in the middle of index update, it updates a
     * single index entry and then stops until the snapshot is done. Compaction thread may also be in the
     * middle of deleting compacted files. In this case, files are deleted first, and snapshot will not take
     * them into consideration.
     *
     * @param snapshotDirectory
     * 		Directory to put snapshot into, it will be created if it doesn't exist.
     * @throws IOException
     * 		If there was a problem snapshotting
     */
    void snapshot(Path snapshotDirectory) throws IOException;
}
