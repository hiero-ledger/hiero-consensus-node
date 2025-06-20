// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.tree;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;

public final class MerkleTreeInternalNode extends PartialBinaryMerkleInternal implements MerkleInternal {

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    public static final long CLASS_ID = 0x1b1c07ad7dc65f17L;

    public MerkleTreeInternalNode() {
        super();
    }

    private MerkleTreeInternalNode(final MerkleTreeInternalNode sourceNode) {
        super(sourceNode);
        setImmutable(false);
        sourceNode.setImmutable(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleTreeInternalNode copy() {
        throwIfImmutable();
        throwIfDestroyed();
        return new MerkleTreeInternalNode(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }
}
