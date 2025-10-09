// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.addressbook.NodeIdList;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Nodes.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public interface ReadableAccountNodeRelStore {

    /**
     * TODO:
     * Returns the node needed. If the node doesn't exist returns failureReason. If the
     * node exists , the failure reason will be null.
     *
     * @param nodeId node id being looked up
     * @return node's metadata
     */
    @Nullable
    NodeIdList get(final AccountID accountId);

    /**
     * Returns the number of nodes in the state.
     * @return the number of nodes in the state
     */
    long sizeOfState();
}
