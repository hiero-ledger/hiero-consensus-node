// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.topology;

import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.network.PeerInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A fully connected topology that never changes.
 */
public class StaticTopology implements NetworkTopology {

    private final Set<NodeId> nodeIds = new HashSet<>();

    private final NodeId selfId;

    /**
     * Constructor.
     * @param peers             the set of peers in the network
     * @param selfId            the ID of this node
     */
    public StaticTopology(@NonNull final List<PeerInfo> peers, @NonNull final NodeId selfId) {
        Objects.requireNonNull(peers);
        Objects.requireNonNull(selfId);
        peers.forEach(peer -> nodeIds.add(peer.nodeId()));
        this.selfId = selfId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<NodeId> getNeighbors() {
        return nodeIds;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldConnectToMe(final NodeId nodeId) {
        return nodeIds.contains(nodeId) && nodeId.id() < selfId.id();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldConnectTo(final NodeId nodeId) {
        return nodeIds.contains(nodeId) && nodeId.id() > selfId.id();
    }
}
