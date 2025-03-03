// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.task;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.views.TeacherPushMerkleTreeView;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A subtree that needs to be sent by the teacher.
 */
public final class TeacherSubtree implements AutoCloseable {

    private final MerkleNode root;
    private final TeacherTreeView<?> view;

    /**
     * Create a subtree with {@link TeacherPushMerkleTreeView}.
     *
     * @param configuration the configuration
     * @param root          the root of the subtree
     */
    public TeacherSubtree(@NonNull final Configuration configuration, final MerkleNode root) {
        this(root, new TeacherPushMerkleTreeView(configuration, root));
    }

    /**
     * Create an object that tracks a subtree that needs to be sent by the teacher.
     *
     * @param root the root of the subtree
     * @param view the view to be used by the subtree
     */
    public TeacherSubtree(final MerkleNode root, final TeacherTreeView<?> view) {
        this.root = root;
        this.view = view;

        if (root != null) {
            root.reserve();
        }
    }

    /**
     * Get the root of the subtree.
     *
     * @return the root
     */
    public MerkleNode getRoot() {
        return root;
    }

    /**
     * Get the view to be used for the subtree.
     *
     * @return a teacher view
     */
    public TeacherTreeView<?> getView() {
        return view;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        view.close();
        if (root != null) {
            root.release();
        }
    }
}
