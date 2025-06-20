// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.communication.multithreaded;

import com.swirlds.base.utility.Pair;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.test.fixtures.communication.TestPeerProtocol;
import com.swirlds.platform.test.fixtures.sync.ConnectionFactory;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Assertions;

/**
 * Constructs a pair of negotiators and a connection they can use to communicate between them
 */
public class NegotiatorPair {
    private final TestNegotiator n1;
    private final TestNegotiator n2;

    public NegotiatorPair(final TestPeerProtocol protocol, final Pair<Connection, Connection> connections) {
        n1 = new TestNegotiator(connections.left(), protocol.copy());
        n2 = new TestNegotiator(connections.right(), protocol.copy());
    }

    public NegotiatorPair(final TestPeerProtocol protocol) throws IOException {
        this(protocol, ConnectionFactory.createLocalConnections(NodeId.of(0L), NodeId.of(1)));
    }

    public void start() {
        n1.getThread().start();
        n2.getThread().start();
    }

    public Callable<Boolean> threadsDone() {
        return () -> {
            for (final TestNegotiator negotiator : List.of(n1, n2)) {
                if (negotiator.getThread().isAlive()) {
                    return false;
                }
            }
            return true;
        };
    }

    public void assertTimesRan(final int times) {
        Assertions.assertEquals(
                times, n1.getProtocol().getTimesRan(), String.format("protocol 1 was expected to run %d times", times));
        Assertions.assertEquals(
                times, n2.getProtocol().getTimesRan(), String.format("protocol 2 was expected to run %d times", times));
    }

    public void assertHandshakeRan() {
        Assertions.assertEquals(1, n1.getHandshakeRunNumber(), "handshake is expected to run exactly once");
        Assertions.assertEquals(1, n2.getHandshakeRunNumber(), "handshake is expected to run exactly once");
    }

    public void finish() throws Exception {
        n1.rethrow();
        n2.rethrow();
    }
}
