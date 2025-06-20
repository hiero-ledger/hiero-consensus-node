// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams.internal;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import java.util.LinkedList;
import java.util.List;

/**
 * Container for holding data gathered during the deserialization MerkleInternal.
 */
public class PartiallyConstructedMerkleInternal {

    private final MerkleInternal node;
    private final int version;
    private final int expectedChildCount;

    private final List<MerkleNode> children;

    public PartiallyConstructedMerkleInternal(MerkleInternal node, int version, int expectedChildCount) {
        this.node = node;
        this.version = version;
        this.expectedChildCount = expectedChildCount;
        this.children = new LinkedList<>();
    }

    public boolean hasAllChildren() {
        return expectedChildCount == children.size();
    }

    public void addChild(MerkleNode child) {
        children.add(child);
    }

    public void finishConstruction() {
        node.addDeserializedChildren(children, version);
    }
}
