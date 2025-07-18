// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;
import static com.swirlds.virtualmap.internal.Path.getLeftChildPath;
import static com.swirlds.virtualmap.internal.Path.getRightChildPath;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.TreeView;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualInternalNode;
import com.swirlds.virtualmap.internal.merkle.VirtualLeafNode;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * A convenient base class for {@link TreeView} implementations for virtual merkle.
 */
public abstract class VirtualTreeViewBase implements TreeView<Long> {
    /**
     * The root node that is involved in reconnect. This would be the saved state for the teacher, and
     * the new root node into which things are being serialized for the learner.
     */
    protected final VirtualMap map;

    /**
     * The state representing the tree being reconnected. For the teacher, this corresponds to the saved state.
     * For the learner, this is the state of the tree being serialized into.
     */
    protected final VirtualMapMetadata reconnectState;

    /**
     * The state representing the original, unmodified tree on the learner. For simplicity, on the teacher,
     * this is the same as {@link #reconnectState}. For the learner, it is the state of the detached, unmodified
     * tree.
     */
    protected final VirtualMapMetadata originalState;

    /**
     * Create a new {@link VirtualTreeViewBase}.
     *
     * @param map
     * 		The map. Cannot be null.
     * @param originalState
     * 		The original state of a learner. Cannot be null.
     * @param reconnectState
     * 		The state of the trees being reconnected. Cannot be null.
     */
    protected VirtualTreeViewBase(
            @NonNull final VirtualMap map,
            @NonNull final VirtualMapMetadata originalState,
            @NonNull final VirtualMapMetadata reconnectState) {
        this.map = Objects.requireNonNull(map);
        this.originalState = Objects.requireNonNull(originalState);
        this.reconnectState = Objects.requireNonNull(reconnectState);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNode getMerkleRoot(final Long node) {
        // NOTE: It is not clear what this "node" is. Original path? New path? It seems to be both depending on the
        // call site. Luckily, it doesn't really matter in my case.
        if (node == null || node == ROOT_PATH) {
            return map;
        }
        throw new UnsupportedOperationException("Nested virtual maps not supported " + node);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInternal(final Long node, final boolean isOriginal) {
        // Sometimes this is null. Sometimes null is considered a leaf.
        if (node == null) {
            return false;
        }

        // Based on isOriginal I can know whether the node is out of the original state or the reconnect state.
        // This only matters on the learner, on the teacher they are both the same instances.
        final VirtualMapMetadata state = isOriginal ? originalState : reconnectState;
        checkValidNode(node, state);
        return node == ROOT_PATH || (node > ROOT_PATH && node < state.getFirstLeafPath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfChildren(final Long originalNode) {
        checkValidNode(originalNode, originalState);
        if (originalNode >= originalState.getFirstLeafPath()) {
            return 0;
        } else if (originalNode == ROOT_PATH) {
            final long lastLeafPath = originalState.getLastLeafPath();
            if (lastLeafPath > 1) {
                return 2;
            } else if (lastLeafPath == 1) {
                return 1;
            } else {
                return 0;
            }
        } else {
            return 2;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId(final Long originalNode) {
        checkValidNode(originalNode, originalState);
        if (originalNode >= originalState.getLastLeafPath()) {
            return VirtualLeafNode.CLASS_ID;
        } else if (originalNode > ROOT_PATH) {
            return VirtualInternalNode.CLASS_ID;
        } else {
            return VirtualMap.CLASS_ID;
        }
    }

    public Long getChild(final Long originalParent, final int childIndex) {
        checkValidInternal(originalParent, originalState);
        assert childIndex >= 0 && childIndex < 2 : "childIndex was not 1 or 2";

        final long childPath = childIndex == 0 ? getLeftChildPath(originalParent) : getRightChildPath(originalParent);

        return childPath > originalState.getLastLeafPath() ? null : childPath;
    }

    protected void checkValidNode(final Long node, final VirtualMapMetadata state) {
        if (node != ROOT_PATH && !(node > ROOT_PATH && node <= state.getLastLeafPath())) {
            throw new MerkleSynchronizationException(
                    "node path out of bounds. path=" + node + ", lastLeafPath=" + state.getLastLeafPath());
        }
    }

    protected void checkValidInternal(final Long node, final VirtualMapMetadata state) {
        if (node != ROOT_PATH && !(node > ROOT_PATH && node < state.getFirstLeafPath())) {
            throw new MerkleSynchronizationException("internal path out of bounds. path=" + node);
        }
    }

    protected void checkValidLeaf(final Long node, final VirtualMapMetadata state) {
        if (node < state.getFirstLeafPath() || node > state.getLastLeafPath()) {
            throw new MerkleSynchronizationException("leaf path out of bounds. path=" + node);
        }
    }
}
