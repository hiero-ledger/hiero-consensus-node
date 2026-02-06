// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * A "view" into a merkle tree (or subtree) used to perform a reconnect operation. This view is used to access
 * the tree by the learner.
 *
 */
public interface LearnerTreeView extends LearnerExpectedLessonQueue, TreeView {

    /**
     * For this tree view, start all required reconnect tasks in the given work group. Learning synchronizer
     * will then wait for all tasks in the work group to complete before proceeding to the next tree view. If
     * new custom tree views are encountered, they must be added to {@code rootsToReceive}, although it isn't
     * currently supported by virtual tree views, as nested virtual maps are not supported.
     *
     * @param learningSynchronizer the learning synchronizer
     * @param workGroup the work group to run teaching task(s) in
     * @param inputStream the input stream to read data from teacher
     * @param outputStream the output stream to write data to teacher
     */
    void startLearnerTasks(
            final LearningSynchronizer learningSynchronizer,
            final StandardWorkGroup workGroup,
            final SerializableDataInputStream inputStream,
            final SerializableDataOutputStream outputStream);

    /**
     * Aborts the reconnect process on the learner side. It may be used to release resources, when
     * reconnect failed with an exception.
     */
    default void abort() {}

    /**
     * Set the child of an internal node.
     *
     * @param parent
     * 		the parent that will hold the child, may be null if the view allows null to represent
     * 		internal nodes in the subtree (although it seems unlikely that such a representation would
     * 		be very useful for views to use)
     * @param childIndex
     * 		the position of the child
     * @param child
     * 		the child, may be null if the view allows null to represent merkle leaf nodes in the subtree
     * @throws MerkleSynchronizationException
     * 		if the parent is not an internal node
     */
    void setChild(Long parent, int childIndex, Long child);

    /**
     * Get the child of a node.
     *
     * @param parent
     * 		the parent in question
     * @param childIndex
     * 		the index of the child
     * @return the child at the index
     * @throws MerkleSynchronizationException
     * 		if the parent is a leaf or if the child index is invalid
     */
    Long getChild(Long parent, int childIndex);

    /**
     * Get the hash of a node. If this view represents a tree that has null nodes within it, those nodes should cause
     * this method to return a {@link Cryptography#NULL_HASH null hash}.
     *
     * @param path
     * 		the node path
     * @return the hash of the node
     */
    Hash getNodeHash(Long path);

    /**
     * Read a merkle leaf from the stream (as written by
     * {@link TeacherTreeView#serializeLeaf(SerializableDataOutputStream, Long)}).
     *
     * @param in
     * 		the input stream
     * @return the leaf
     * @throws IOException
     * 		if a problem is encountered with the stream
     */
    Long deserializeLeaf(SerializableDataInputStream in) throws IOException;

    /**
     * Read a merkle internal from the stream (as written by
     * {@link TeacherTreeView#serializeInternal(SerializableDataOutputStream, Long)}).
     *
     * @param in
     * 		the input stream
     * @return the internal node
     * @throws IOException
     * 		if a problem is encountered with the stream
     */
    Long deserializeInternal(SerializableDataInputStream in) throws IOException;

    /**
     * Record metrics related to queries about children of a given parent during reconnect.
     * <p>
     * By the definition of this method, it is obvious that the parent is an internal node in the tree.
     * However, the children may be the next level internal nodes or leaf nodes.
     * The metrics differentiate between internal and leaf children. The method of determining
     * whether a given child is an internal node or a leaf may depend on the LearnerTreeView implementation,
     * and this method allows the implementation to define this logic as appropriate.
     *
     * @param mapStats a metrics recorder
     * @param parent a parent
     * @param childIndex a child index, same as a lesson query index
     * @param nodeAlreadyPresent true if the learner tree has the query node already
     */
    default void recordHashStats(
            @NonNull final ReconnectMapStats mapStats,
            @NonNull final Long parent,
            final int childIndex,
            final boolean nodeAlreadyPresent) {
        // no-op
    }
}
