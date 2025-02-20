// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.swirlds.common.merkle.MerkleNode;

public interface BenchmarkMerkleNode extends MerkleNode {

    /**
     * Get a String value representing this node.
     */
    String getValue();
}
