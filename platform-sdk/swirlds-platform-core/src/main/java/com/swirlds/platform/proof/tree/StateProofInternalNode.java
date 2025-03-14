// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.proof.tree;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An internal node in a state proof tree.
 */
public class StateProofInternalNode extends AbstractStateProofNode {

    /**
     * Child state proof nodes.
     */
    private List<StateProofNode> children;

    /**
     * A scratchpad variable used when iterating the state proof tree (when recomputing the root hash).
     */
    private boolean visited = false;

    /**
     * Zero arg constructor required by the serialization framework.
     */
    public StateProofInternalNode() {}

    /**
     * Construct a new state proof internal node from the given merkle internal node.
     *
     * @param children the children of the internal node
     */
    public StateProofInternalNode(@NonNull final List<StateProofNode> children) {
        this.children = Objects.requireNonNull(children);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void computeHashableBytes(@NonNull final MessageDigest digest) {
        if (children == null) {
            throw new IllegalStateException("StateProofInternalNode has not been properly initialized");
        }

        for (final StateProofNode child : children) {
            digest.update(child.getHashableBytes());
        }

        setHashableBytes(digest.digest());
        digest.reset();
    }

    /**
     * Get the child state proof nodes.
     */
    @NonNull
    public List<StateProofNode> getChildren() {
        if (children == null) {
            children = new ArrayList<>();
        }
        return children;
    }

    /**
     * Mark this node as having been visited during a traversal of the state proof tree. Used when recomputing the root
     * hash.
     */
    public void markAsVisited() {
        if (visited) {
            throw new IllegalStateException("Node has already been visited");
        }
        visited = true;
    }

    /**
     * Check if this node has been visited during a traversal of the state proof tree. Used during state proof
     * validation.
     *
     * @return true if {@link #markAsVisited()} has been called on this node, false otherwise
     */
    public boolean hasBeenVisited() {
        return visited;
    }
}
