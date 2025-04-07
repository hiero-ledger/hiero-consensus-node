// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.network;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.ConnectionManager;
import com.swirlds.platform.network.ConnectionTracker;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.topology.ConnectionManagerFactory;
import com.swirlds.platform.test.fixtures.sync.FakeConnection;
import edu.umd.cs.findbugs.annotations.NonNull;
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
            @NonNull NodeId selfId,
            @NonNull PeerInfo otherPeer,
            @NonNull PlatformContext platformContext,
            @NonNull ConnectionTracker connectionTracker,
            @NonNull KeysAndCerts ownKeysAndCerts) {
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
