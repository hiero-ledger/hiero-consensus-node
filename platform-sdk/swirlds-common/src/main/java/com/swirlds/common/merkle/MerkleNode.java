// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.Reservable;
import com.swirlds.common.merkle.interfaces.MerkleMigratable;
import com.swirlds.common.merkle.interfaces.MerkleTraversable;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteIterator;
import com.swirlds.common.merkle.synchronization.views.MaybeCustomReconnectRoot;
import org.hiero.consensus.model.crypto.Hashable;
import org.hiero.consensus.model.io.SerializableDet;

/**
 * A MerkleNode object has the following properties
 * <ul>
 *     <li>Doesn't need to compute its hash</li>
 *     <li>It's not aware of Cryptographic Modules</li>
 *     <li>Doesn't need to perform rsync</li>
 *     <li>Doesn't need to provide hints to the Crypto Module</li>
 * </ul>
 */
public interface MerkleNode
        extends FastCopyable,
                Hashable,
                MerkleMigratable,
                MerkleTraversable,
                MaybeCustomReconnectRoot,
                Reservable,
                SerializableDet {

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    MerkleNode copy();

    /**
     * {@inheritDoc}
     */
    @Override
    default MerkleNode migrate(final int version) {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default MerkleNode getNodeAtRoute(final MerkleRoute route) {
        return new MerkleRouteIterator(this, route).getLast();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default <T extends MerkleNode> MerkleIterator<T> treeIterator() {
        return new MerkleIterator<>(this);
    }
}
