// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.modular;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.gossip.impl.network.protocol.PeerProtocol;
import org.hiero.consensus.gossip.impl.network.protocol.Protocol;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

public class TestProtocol implements Protocol {

    private final NodeId selfId;
    private final List<CommunicationEvent> events;
    private final List<TestPeerProtocol> peerProtocols;

    public TestProtocol(NodeId selfId, List<CommunicationEvent> events, List<TestPeerProtocol> peerProtocols) {
        this.selfId = selfId;
        this.events = events;
        this.peerProtocols = peerProtocols;
    }

    @Override
    public PeerProtocol createPeerInstance(@NonNull NodeId otherId) {
        var tpp = new TestPeerProtocol(selfId, otherId, events);
        peerProtocols.add(tpp);
        return tpp;
    }

    @Override
    public void updatePlatformStatus(@NonNull PlatformStatus status) {
        // no-op, we don't care
    }
}
