// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

import com.swirlds.common.merkle.MerkleNode;

public interface DummyMerkleNode extends MerkleNode {

    /**
     * Get a String value representing this node.
     */
    String getValue();
}
