// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.info;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Provides information about the network.
 */
public interface NetworkInfo {
    /**
     * Returns the current ledger ID.
     * @return the {@link Bytes} of the current ledger ID
     */
    @NonNull
    Bytes ledgerId();

    @NonNull
    NodeInfo selfNodeInfo();

    @NonNull
    List<NodeInfo> addressBook();

    @Nullable
    NodeInfo nodeInfo(long nodeId);

    /**
     * Returns true if the network contains a node with the given ID.
     *
     * @param nodeId the ID of the node to check for
     * @return true if the network contains a node with the given ID
     */
    boolean containsNode(long nodeId);

    /**
     * Updates the network information from the given state. This method is called when the
     * state is updated using node updates.
     *
     * @param state the state to update from
     */
    void updateFrom(State state);
}
