// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.protocol.PeerProtocol;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;

public class TestPeerProtocol implements PeerProtocol {

    private final NodeId otherId;
    private final NodeId selfId;
    private final List<CommunicationEvent> events;
    int counter = 0;
    long lastExchange = 0;
    long shouldInitiate = 0;
    long shouldAccept = 0;

    public TestPeerProtocol(NodeId selfId, NodeId otherId, List<CommunicationEvent> events) {
        this.selfId = selfId;
        this.otherId = otherId;
        this.events = events;
    }

    @Override
    public boolean shouldInitiate() {
        shouldInitiate++;
        return System.currentTimeMillis() - lastExchange > 500;
    }

    @Override
    public boolean shouldAccept() {
        shouldAccept++;
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

    public String getDebugInfo() {
        return selfId + "->"
                + otherId + ", "
                + "counter=" + counter + ", shouldInitiate="
                + shouldInitiate + ", shouldAccept="
                + shouldAccept + ", lastExchange "
                + Instant.ofEpochMilli(lastExchange);
    }
}
