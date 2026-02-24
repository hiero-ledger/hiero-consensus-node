// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook;

import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with registered node state.
 */
public interface ReadableRegisteredNodeStore {
    /**
     * Returns the registered node with the given id, or {@code null} if it does not exist.
     *
     * @param registeredNodeId the registered node id
     * @return the registered node, or {@code null}
     */
    @Nullable
    RegisteredNode get(long registeredNodeId);
}
