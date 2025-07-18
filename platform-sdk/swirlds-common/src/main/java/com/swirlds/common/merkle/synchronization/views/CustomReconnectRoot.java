// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Nodes that are want to use a custom view for reconnect must extend this interface and
 * return true for {@link MerkleNode#hasCustomReconnectView() hasCustomReconnectView()}.
 *
 * @param <T>
 * 		the type used by the view for the teacher
 * @param <L>
 * 		the type used by the view for the learner
 */
public interface CustomReconnectRoot<T, L> extends MerkleNode {

    /**
     * <p>
     * Build a view of this subtree to be used for reconnect by the teacher.
     * </p>
     *
     * <p>
     * It is ok if this view is not immediately ready for use, as long as the view eventually
     * becomes ready for use (presumably when some background task has completed). If this is
     * the case, then the {@link TeacherTreeView#waitUntilReady()} on the returned view should
     * block until the view is ready to be used to perform a reconnect.
     * </p>
     *
     * @return a view representing this subtree
     */
    TeacherTreeView<T> buildTeacherView(@NonNull final ReconnectConfig reconnectConfig);

    /**
     * Build a view of this subtree to be used for reconnect by the learner.
     *
     * @param mapStats a ReconnectMapStats object to collect reconnect metrics
     * @return a view representing this subtree
     */
    LearnerTreeView<L> buildLearnerView(
            final ReconnectConfig reconnectConfig, @NonNull final ReconnectMapStats mapStats);

    /**
     * If the original node in this position is of the correct type then the learner's node is initialized via
     * this method. After this method is called, this node and its subtree should be entirely hashed, mutable,
     * and should not cause the original to modified.
     *
     * @param originalNode
     * 		the original node in the learner's tree in the root position of this subtree
     */
    void setupWithOriginalNode(final MerkleNode originalNode);

    /**
     * Called on a node if there is no data to be copied.
     */
    void setupWithNoData();

    /**
     * Creates a new empty root instance of the same type and registers the current instance with
     * {@link #setupWithOriginalNode(com.swirlds.common.merkle.MerkleNode)}
     *
     * @return new empty root instance
     */
    CustomReconnectRoot<T, L> createNewRoot();

    /**
     * {@inheritDoc}
     */
    @Override
    default boolean hasCustomReconnectView() {
        return true;
    }
}
