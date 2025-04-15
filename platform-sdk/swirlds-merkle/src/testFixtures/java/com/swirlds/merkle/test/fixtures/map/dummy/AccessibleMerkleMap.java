// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.dummy;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.fchashmap.FCHashMap;
import com.swirlds.merkle.map.MerkleMap;
import org.hiero.base.constructable.ConstructableIgnored;

/**
 * A MerkleMap with several methods publicly exposed.
 */
@ConstructableIgnored
public class AccessibleMerkleMap<K, V extends MerkleNode & Keyed<K>> extends MerkleMap<K, V> {

    public AccessibleMerkleMap() {
        super();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FCHashMap<K, V> getIndex() {
        return super.getIndex();
    }
}
