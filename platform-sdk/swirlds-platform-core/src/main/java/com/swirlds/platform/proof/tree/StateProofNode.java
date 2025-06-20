// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.proof.tree;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;

/**
 * A node in a state proof tree.
 */
public interface StateProofNode {

    /**
     * Compute the bytes that this node contributes to its parent's hash, and store those bytes and return them when
     * {@link #getHashableBytes()} is called. This method is called on each state proof node in a post-ordered depth
     * first traversal of the state proof tree.
     *
     * @param digest builds running hashes
     * @throws IllegalStateException if this method is called before this object has been fully deserialized
     */
    void computeHashableBytes(@NonNull final MessageDigest digest);

    /**
     * Get the bytes that this node contributes to its parent's hash. Guaranteed to be called after
     * {@link #computeHashableBytes(MessageDigest)}.
     *
     * @return the bytes that this node contributes to its parent's hash
     * @throws IllegalStateException if this method is called before this object has been fully deserialized
     */
    @NonNull
    byte[] getHashableBytes();
}
