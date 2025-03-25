// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication.multithreaded;

import static org.awaitility.Awaitility.await;

import com.swirlds.base.utility.Pair;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.test.fixtures.communication.TestPeerProtocol;
import com.swirlds.platform.test.fixtures.communication.multithreaded.ExpiringConnection;
import com.swirlds.platform.test.fixtures.communication.multithreaded.NegotiatorPair;
import com.swirlds.platform.test.fixtures.sync.ConnectionFactory;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests negotiations that are executed on separate threads and communicate directly over a connection
 */
class NegotiatorMultithreadedTest {
    @DisplayName("Negotiate and run a protocol on 2 threads talking to each other")
    @Test
    void runProtocol() throws Exception {
        final NegotiatorPair pair = new NegotiatorPair(new TestPeerProtocol()
                .setShouldAccept(true)
                .setShouldInitiate(true)
                .setAcceptOnSimultaneousInitiate(true));
        pair.start();
        await().atMost(5, TimeUnit.SECONDS).until(pair.threadsDone());
        pair.assertTimesRan(1);
        pair.assertHandshakeRan();
        pair.finish();
    }

    @DisplayName("Both negotiators send keepalive because there is no protocol to initiate")
    @Test
    void keepalive() throws Exception {
        final Pair<Connection, Connection> connections =
                ConnectionFactory.createLocalConnections(NodeId.of(0L), NodeId.of(1));
        final NegotiatorPair pair = new NegotiatorPair(
                new TestPeerProtocol().setShouldInitiate(false),
                Pair.of(new ExpiringConnection(connections.left(), 1), new ExpiringConnection(connections.right(), 1)));
        pair.start();
        await().atMost(5, TimeUnit.SECONDS).until(pair.threadsDone());
        pair.assertTimesRan(0);
        pair.assertHandshakeRan();
        pair.finish();
    }
}
