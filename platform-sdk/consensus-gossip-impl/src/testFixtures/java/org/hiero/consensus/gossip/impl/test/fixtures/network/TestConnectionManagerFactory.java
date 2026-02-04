// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.test.fixtures.network;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.ConnectionManager;
import org.hiero.consensus.gossip.impl.network.ConnectionTracker;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.gossip.impl.network.topology.ConnectionManagerFactory;
import org.hiero.consensus.gossip.impl.test.fixtures.sync.FakeConnection;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

public class TestConnectionManagerFactory implements ConnectionManagerFactory {

    private final NodeId selfId;

    public TestConnectionManagerFactory(NodeId selfId) {
        this.selfId = selfId;
    }

    @Override
    public ConnectionManager createInboundConnectionManager(@NonNull final PeerInfo otherPeer) {
        return new ConnectionManager() {
            Connection connection;

            @Override
            public Connection waitForConnection() throws InterruptedException {
                return connection;
            }

            @Override
            public Connection getConnection() {
                return connection;
            }

            @Override
            public void newConnection(Connection connection) throws InterruptedException {
                if (this.connection != null) {
                    this.connection.disconnect();
                }
                this.connection = connection;
            }

            @Override
            public boolean isOutbound() {
                return false;
            }
        };
    }

    @Override
    public ConnectionManager createOutboundConnectionManager(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final PeerInfo otherPeer,
            @NonNull final ConnectionTracker connectionTracker,
            @NonNull final KeysAndCerts ownKeysAndCerts) {
        return new ConnectionManager() {

            @Override
            public Connection waitForConnection() throws InterruptedException {
                return new FakeConnection(selfId, otherPeer.nodeId());
            }

            @Override
            public Connection getConnection() {
                return null;
            }

            @Override
            public void newConnection(Connection connection) throws InterruptedException {
                throw new UnsupportedOperationException("Does not accept connections");
            }

            @Override
            public boolean isOutbound() {
                return true;
            }
        };
    }
}
