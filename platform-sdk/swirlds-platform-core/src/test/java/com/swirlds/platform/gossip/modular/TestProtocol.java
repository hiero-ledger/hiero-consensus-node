// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.protocol.PeerProtocol;
import com.swirlds.platform.network.protocol.Protocol;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;

public class TestProtocol implements Protocol {

    private final NodeId selfId;
    private final List<CommunicationEvent> events;

    public TestProtocol(NodeId selfId, List<CommunicationEvent> events) {
        this.selfId = selfId;
        this.events = events;
    }

    @Override
    public PeerProtocol createPeerInstance(@NonNull NodeId otherId) {
        return new TestPeerProtocol(selfId, otherId, events);
    }
}

class TestPeerProtocol implements PeerProtocol {

    private final NodeId otherId;
    private final NodeId selfId;
    private final List<CommunicationEvent> events;
    int counter = 0;
    long lastExchange = 0;

    public TestPeerProtocol(NodeId selfId, NodeId otherId, List<CommunicationEvent> events) {
        this.selfId = selfId;
        this.otherId = otherId;
        this.events = events;
    }

    @Override
    public boolean shouldInitiate() {
        return System.currentTimeMillis() - lastExchange > 500;
    }

    @Override
    public boolean shouldAccept() {
        return true;
    }

    @Override
    public boolean acceptOnSimultaneousInitiate() {
        return true;
    }

    @Override
    public void runProtocol(Connection connection) throws NetworkProtocolException, IOException, InterruptedException {
        connection.getDos().writeLong(selfId.id());
        connection.getDos().writeInt(counter++);
        connection.getDos().flush();
        var otherId = connection.getDis().readLong();
        var otherCounter = connection.getDis().readInt();

        if (otherId != connection.getOtherId().id()) {
            throw new NetworkProtocolException("Mismatching node id received " + otherId + " expected "
                    + connection.getOtherId().id());
        }
        this.lastExchange = System.currentTimeMillis();
        synchronized (events) {
            events.add(new CommunicationEvent(selfId.id(), counter - 1, otherId, otherCounter, this.lastExchange));
        }
    }
}
