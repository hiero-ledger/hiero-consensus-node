// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.topology;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.model.node.NodeId;

/**
 * A fully connected topology that never changes.
 */
public class StaticTopology implements NetworkTopology {

    @NonNull
    private final Set<NodeId> nodeIds;

    private final NodeId selfId;

    /**
     * Constructor.
     *
     * @param peers the set of peers in the network
     * @param selfId the ID of this node
     */
    public StaticTopology(@NonNull final List<PeerInfo> peers, @NonNull final NodeId selfId) {
        Objects.requireNonNull(peers);
        Objects.requireNonNull(selfId);
        nodeIds = peers.stream().map(PeerInfo::nodeId).collect(Collectors.toUnmodifiableSet());
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
    public boolean shouldConnectToMe(@NonNull final NodeId nodeId) {
        return nodeIds.contains(nodeId) && nodeId.id() < selfId.id();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldConnectTo(@NonNull final NodeId nodeId) {
        return nodeIds.contains(nodeId) && nodeId.id() > selfId.id();
    }
}
