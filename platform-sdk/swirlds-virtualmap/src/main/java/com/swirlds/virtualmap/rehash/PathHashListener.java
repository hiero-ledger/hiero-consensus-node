// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.rehash;

import edu.umd.cs.findbugs.annotations.NonNull;

/// Listens to node level events that occur during [FullRehashNodeHasher] hashing.
public interface PathHashListener {

    PathHashListener NO_OP = (path, hash) -> {};

    /// Called when hashing is started, before any [#onHashed(long, byte[])] calls.
    ///
    /// @param firstLeafPath the first leaf path in the virtual tree
    /// @param lastLeafPath the last leaf path in the virtual tree
    default void onHashingStarted(final long firstLeafPath, final long lastLeafPath) {}

    /// Called after every node, either a leaf or an internal node, is hashed. Called from
    /// hashing threads, possibly in parallel. For every internal node, this method is called
    /// for both its children before it's called for the node itself, and the children calls
    /// happen-before the node call.
    ///
    /// @param path the node path
    /// @param hash the node hash bytes. Must not be modified
    void onHashed(long path, @NonNull byte[] hash);

    /// Called when all hashing has completed. Not called if hashing fails.
    default void onHashingCompleted() {}
}
