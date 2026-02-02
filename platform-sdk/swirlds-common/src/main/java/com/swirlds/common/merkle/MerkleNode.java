// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.Reservable;
import com.swirlds.common.merkle.interfaces.HasMerkleRoute;
import com.swirlds.common.merkle.interfaces.MerkleMigratable;
import com.swirlds.common.merkle.interfaces.MerkleType;
import org.hiero.base.crypto.Hashable;
import org.hiero.base.io.SerializableDet;

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
        extends FastCopyable, Hashable, MerkleMigratable, Reservable, SerializableDet, MerkleType, HasMerkleRoute {

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
}
