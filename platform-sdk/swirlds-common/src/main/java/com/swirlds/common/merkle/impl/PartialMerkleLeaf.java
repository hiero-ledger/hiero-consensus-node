// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.impl;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.internal.AbstractMerkleNode;

/**
 * This abstract implements boilerplate functionality for a {@link MerkleLeaf}. Classes that implement
 * {@link MerkleLeaf} are not required to extend this class, but absent a reason it is recommended to avoid
 * re-implementation of this code.
 */
public non-sealed class PartialMerkleLeaf extends AbstractMerkleNode {

    public PartialMerkleLeaf() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isLeaf() {
        return true;
    }

    /**
     * Copy constructor.
     */
    protected PartialMerkleLeaf(final PartialMerkleLeaf that) {
        super(that);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected final void onDestroy() {
        destroyNode();
    }
}
