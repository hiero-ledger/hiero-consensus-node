// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook;

import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.function.Function;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Nodes.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public interface ReadableNodeStore {

    /**
     * Constructs a new {@link Roster} object using the current info for each node defined in state.
     * Accordingly, be warned that <b>this method iterates over all nodes.</b>
     *
     * @param weightFunction the function to use to determine the weight of each node
     *                       from stakingNodeInfo
     * @return a new roster, representing the most current node configurations available
     */
    Roster snapshotOfFutureRoster(Function<Long, Long> weightFunction);

    /**
     * Returns the node needed. If the node doesn't exist returns failureReason. If the
     * node exists , the failure reason will be null.
     *
     * @param nodeId node id being looked up
     * @return node's metadata
     */
    @Nullable
    Node get(long nodeId);

    /**
     * Returns the number of nodes in the state.
     * @return the number of nodes in the state
     */
    long sizeOfState();

    /**
     * Warms the system by preloading a node into memory
     *
     * <p>The default implementation is empty because preloading data into memory is only used for some implementations.
     *
     * @param nodeId the node id
     */
    default void warm(final long nodeId) {}

    /**
     * Returns a list of all the keys in the state.
     * @return a list of all the keys in the state.
     */
    @NonNull
    List<EntityNumber> keys();
}
