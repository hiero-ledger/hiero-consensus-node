// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import java.io.IOException;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * A "view" into a merkle tree (or subtree) used to perform a reconnect operation. This view is used to access
 * the tree by the teacher.
 *
 */
public interface TeacherTreeView extends TeacherHandleQueue, TeacherResponseQueue, TeacherResponseTracker, TreeView {

    /**
     * For this tree view, start all required reconnect tasks in the given work group. Teaching synchronizer
     * will then wait for all tasks in the work group to complete before proceeding to the next tree view. If
     * new custom tree views are encountered, they must be added to {@code subtrees}, although it isn't
     * currently supported by virtual tree views, as nested virtual maps are not supported.
     *
     * @param teachingSynchronizer the teacher synchronizer
     * @param workGroup the work group to run teaching task(s) in
     * @param in the input stream to read data from learner
     * @param out the output stream to write data to learner
     */
    void startTeacherTasks(
            final TeachingSynchronizer teachingSynchronizer,
            final Time time,
            final StandardWorkGroup workGroup,
            final AsyncInputStream in,
            final AsyncOutputStream out);

    /**
     * Write data for a merkle leaf to the stream.
     *
     * @param out
     * 		the output stream
     * @param leafPath
     * 		the merkle leaf path
     * @throws IOException
     * 		if an IO problem occurs
     * @throws MerkleSynchronizationException
     * 		if the node is not a leaf
     */
    void serializeLeaf(SerializableDataOutputStream out, Long leafPath) throws IOException;

    /**
     * Serialize data required to reconstruct an internal node. Should not contain any
     * data about children, number of children, or any metadata (i.e. data that is not hashed).
     *
     * @param out
     * 		the output stream
     * @param internalPath
     * 		the internal node path to serialize
     * @throws IOException
     * 		if a problem is encountered with the stream
     */
    void serializeInternal(SerializableDataOutputStream out, Long internalPath) throws IOException;

    /**
     * Serialize all child hashes for a given node into a stream. Serialized bytes must be
     * identical to what out.writeSerializableList(hashesList, false, true) method writes.
     *
     * @param parentPath node path
     * @param out The output stream
     * @throws IOException If an I/O error occurred
     */
    void writeChildHashes(Long parentPath, SerializableDataOutputStream out) throws IOException;
}
