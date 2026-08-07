// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.modular;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.main.model.PeerProtocol;
import org.hiero.consensus.main.model.PeerProtocolFactory;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

public class TestPeerProtocolFactory implements PeerProtocolFactory {

    private final NodeId selfId;
    private final List<CommunicationEvent> events;
    private final List<TestPeerProtocol> peerProtocols;

    public TestPeerProtocolFactory(NodeId selfId, List<CommunicationEvent> events, List<TestPeerProtocol> peerProtocols) {
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
