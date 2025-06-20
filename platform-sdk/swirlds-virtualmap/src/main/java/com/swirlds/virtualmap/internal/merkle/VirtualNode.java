// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import com.swirlds.common.merkle.MerkleNode;

/**
 * A base interface for both {@link VirtualInternalNode} and {@link VirtualLeafNode}.
 */
public sealed interface VirtualNode extends MerkleNode permits VirtualInternalNode, VirtualLeafNode {

    /**
     * Get the path for this node.
     */
    long getPath();
}
