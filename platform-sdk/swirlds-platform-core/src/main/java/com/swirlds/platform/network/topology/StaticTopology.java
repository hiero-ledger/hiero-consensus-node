// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.topology;

import com.google.common.collect.ImmutableSet;
import com.swirlds.platform.network.PeerInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

/**
 * A fully connected topology that never changes.
 */
public class StaticTopology implements NetworkTopology {

    private final ImmutableSet<NodeId> nodeIds;

    private final NodeId selfId;

    /**
     * Constructor.
     * @param peers             the set of peers in the network
     * @param selfId            the ID of this node
     */
    public StaticTopology(@NonNull final List<PeerInfo> peers, @NonNull final NodeId selfId) {
        Objects.requireNonNull(peers);
        Objects.requireNonNull(selfId);
        ImmutableSet.Builder<NodeId> builder = ImmutableSet.builder();

        peers.forEach(peer -> builder.add(peer.nodeId()));
        nodeIds = builder.build();
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
