// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import com.swirlds.common.merkle.MerkleInternal;

/**
 * Methods used by the learner to initialize nodes within a view.
 *
 * @param <T>
 * 		the type of object used to represent merkle nodes in the view
 */
public interface LearnerInitializer<T> {

    /**
     * Mark a node for later initialization. If a node type is known not to require
     * initialization, no action is required.
     *
     * @param node
     * 		the node to later initialize
     */
    void markForInitialization(T node);

    /**
     * <p>
     * Initialize each internal node that was reconstructed via this algorithm by calling
     * {@link MerkleInternal#rebuild()}. No action is required for node types that are known
     * to not require initialization.
     * </p>
     *
     * <p>
     * Initialization is required to initialize children before their parents.
     * </p>
     *
     * <p>
     * This method is called exactly once when the synchronization algorithm has completely transferred the entire tree.
     * </p>
     *
     * <p>
     * It is ok if this method is also used to initialize other parts or components of the tree.
     * </p>
     */
    void initialize();
}
