// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid;

/**
 * Generates unique node IDs based on the highest node ID singleton.
 */
public interface NodeIdGenerator {
    /**
     * Allocates and returns a new unique node id.
     */
    long newNodeId();

    /**
     * Returns the next node id that would be allocated, without changing state.
     */
    long peekAtNewNodeId();
}
