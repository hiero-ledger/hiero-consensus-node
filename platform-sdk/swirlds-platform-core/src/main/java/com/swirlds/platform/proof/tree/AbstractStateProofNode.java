// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.proof.tree;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Boilerplate for state proof tree nodes.
 */
public abstract class AbstractStateProofNode implements StateProofNode {

    private byte[] hashableBytes;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public final byte[] getHashableBytes() {
        if (hashableBytes == null) {
            throw new IllegalStateException("Hashable bytes have not been computed");
        }
        return hashableBytes;
    }

    /**
     * Set the hashable bytes for this node.
     *
     * @param hashableBytes the hashable bytes
     */
    protected void setHashableBytes(@NonNull byte[] hashableBytes) {
        Objects.requireNonNull(hashableBytes);
        if (this.hashableBytes != null) {
            throw new IllegalArgumentException("setHashableBytes() called more than once");
        }
        this.hashableBytes = hashableBytes;
    }
}
