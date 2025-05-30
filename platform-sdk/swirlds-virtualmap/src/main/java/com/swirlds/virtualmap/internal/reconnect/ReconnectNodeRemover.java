// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.RecordAccessor;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * During reconnect, information about all existing nodes is sent from the teacher to the learner. However,
 * the leaner may have old nodes in its virtual tree, and these nodes aren't transferred from the teacher.
 * This class is used to identify such stale nodes in the learner tree. The list of nodes to remove is then
 * used during flushes that happen periodically during reconnects (during hashing).
 *
 * <p>Node remover gets notifications about internal and leaf nodes received from the teacher during reconnect
 * and checks what nodes were in the learner (original) virtual tree at the corresponding paths. For example,
 * if an internal node is received for a path from the teacher, but it was a leaf node on the learner, the
 * key (that corresponds to that leaf) needs to removed. Other cases are handled in a similar way.
 *
 * <p>One particular case is complicated. Assume the learner tree has a key K at path N, and the teacher has
 * the same key at path M, and M &lt; N, while at path N the teacher has a different key L. During reconnects the
 * path M is processed first. At this step, some old key is marked for removal. Some time later path N is
 * processed, and this time key K is marked for removal, but this is wrong as it's still a valid key, just at
 * a different path. To handle this case, during flushes we check all leaf candidates for removal, where in
 * the tree they are located. In the scenario above, when path M was processed, key location was changed from N
 * to M. Later during flush, key K is in the list of candidates to remove, but with path N (this is where it
 * was originally located in the learner tree). Since the path is different, the leaf will not be actually
 * removed from disk.
 *
 * @param <K>
 * 		the type of the key
 * @param <V>
 * 		the type of the value
 */
public class ReconnectNodeRemover<K extends VirtualKey, V extends VirtualValue> {

    private static final Logger logger = LogManager.getLogger(ReconnectNodeRemover.class);

    /**
     * The last leaf path of the new tree being received.
     */
    private long newLastLeafPath;

    /**
     * Can be used to access the tree being constructed.
     */
    private final RecordAccessor oldRecords;

    /**
     * The first leaf path of the original merkle tree.
     */
    private final long oldFirstLeafPath;

    /**
     * The last leaf path of the original merkle tree.
     */
    private final long oldLastLeafPath;

    /**
     * A flusher used to delete old leaves from the data source.
     */
    private final ReconnectHashLeafFlusher flusher;

    /**
     * Create an object responsible for removing virtual map nodes during a reconnect.
     *
     * @param oldRecords
     * 		a record accessor for the original map from before the reconnect
     * @param oldFirstLeafPath
     * 		the original first leaf path from before the reconnect
     * @param oldLastLeafPath
     * 		the original last leaf path from before the reconnect
     */
    public ReconnectNodeRemover(
            @NonNull final RecordAccessor oldRecords,
            final long oldFirstLeafPath,
            final long oldLastLeafPath,
            @NonNull final ReconnectHashLeafFlusher flusher) {
        this.oldRecords = oldRecords;
        this.oldFirstLeafPath = oldFirstLeafPath;
        this.oldLastLeafPath = oldLastLeafPath;
        this.flusher = flusher;
    }

    /**
     * Set the first/last leaf path for the tree as it will be after reconnect is completed. Expected
     * to be called before the first node is passed to this object.
     *
     * <p>If the old learner tree contained fewer elements than the new tree from the teacher, all leaves
     * from the old {@code firstLeafPath} inclusive to the new {@code firstLeafPath} exclusive are
     * marked for deletion.
     *
     * @param newFirstLeafPath
     * 		the first leaf path after reconnect completes
     * @param newLastLeafPath
     * 		the last leaf path after reconnect completes
     */
    public synchronized void setPathInformation(final long newFirstLeafPath, final long newLastLeafPath) {
        logger.info(
                RECONNECT.getMarker(),
                "setPathInformation(): firstLeafPath: {} -> {}, lastLeafPath: {} -> {}",
                oldFirstLeafPath,
                newFirstLeafPath,
                oldLastLeafPath,
                newLastLeafPath);
        flusher.start(newFirstLeafPath, newLastLeafPath);
        this.newLastLeafPath = newLastLeafPath;
        if (oldLastLeafPath > 0) {
            // no-op if new first leaf path is less or equal to old first leaf path
            for (long path = oldFirstLeafPath; path < Math.min(newFirstLeafPath, oldLastLeafPath + 1); path++) {
                final VirtualLeafBytes oldRecord = oldRecords.findLeafRecord(path);
                assert oldRecord != null;
                flusher.deleteLeaf(oldRecord);
            }
        }
        logger.info(RECONNECT.getMarker(), "setPathInformation(): done");
    }

    /**
     * Register the receipt of a new leaf node. If the leaf node is in the position that was formally occupied by
     * a leaf node then this method will ensure that the old leaf node is properly deleted.
     *
     * @param path
     * 		the path to the node
     * @param newKey
     * 		the key of the new leaf node
     */
    public synchronized void newLeafNode(final long path, final Bytes newKey) {
        final VirtualLeafBytes oldRecord = oldRecords.findLeafRecord(path);
        if ((oldRecord != null) && !newKey.equals(oldRecord.keyBytes())) {
            flusher.deleteLeaf(oldRecord);
        }
    }

    public synchronized void allNodesReceived() {
        logger.info(RECONNECT.getMarker(), "allNodesReceived()");
        final long firstOldStalePath = (newLastLeafPath == Path.INVALID_PATH) ? 1 : newLastLeafPath + 1;
        // No-op if newLastLeafPath is greater or equal to oldLastLeafPath
        for (long p = firstOldStalePath; p <= oldLastLeafPath; p++) {
            final VirtualLeafBytes oldExtraLeafRecord = oldRecords.findLeafRecord(p);
            assert oldExtraLeafRecord != null || p < oldFirstLeafPath;
            if (oldExtraLeafRecord != null) {
                flusher.deleteLeaf(oldExtraLeafRecord);
            }
        }
        logger.info(RECONNECT.getMarker(), "allNodesReceived(): done");
    }
}
