// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.platform.network.connectivity.OutboundConnectionCreator;
import com.swirlds.platform.test.fixtures.sync.FakeConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

class LegacyOutboundConnectionManagerTest {
    @Test
    void createConnectionTest() {
        final NodeId nodeId = NodeId.of(0L);
        ;
        final Connection connection1 = new FakeConnection();
        final Connection connection2 = new FakeConnection();
        final OutboundConnectionCreator creator = mock(OutboundConnectionCreator.class);
        final LegacyOutboundConnectionManager manager = new LegacyOutboundConnectionManager(nodeId, creator);

        assertThrows(
                Exception.class, () -> manager.newConnection(new FakeConnection()), "this method is not supported");
        assertFalse(manager.getConnection().connected(), "initially it should return a disconnected connection");
        doAnswer(t -> connection1).when(creator).createConnection(nodeId);
        assertSame(
                connection1,
                manager.waitForConnection(),
                "it should have returned the new connection returned by the creator");
        assertTrue(connection1.connected(), "it should be connected");
        assertSame(connection1, manager.getConnection(), "this method should always return the latest connection");
        assertSame(
                connection1,
                manager.waitForConnection(),
                "the same connection should be returned while it is still connected");

        connection1.disconnect();
        doAnswer(t -> connection2).when(creator).createConnection(nodeId);
        assertSame(connection1, manager.getConnection(), "this method should always return the latest connection");
        assertSame(
                connection2,
                manager.waitForConnection(),
                "because connection1 is now disconnected, we should receive connection2");
    }

    @Test
    void concurrencyTest() throws InterruptedException {
        final int numThreads = 10;
        final NodeId nodeId = NodeId.of(0L);
        final OutboundConnectionCreator creator = mock(OutboundConnectionCreator.class);
        final Connection connection = new FakeConnection();
        final CountDownLatch waitingForConnection = new CountDownLatch(1);
        when(creator.createConnection(any())).thenAnswer(i -> {
            waitingForConnection.await();
            return connection;
        });
        final LegacyOutboundConnectionManager manager = new LegacyOutboundConnectionManager(nodeId, creator);

        final List<Thread> threads = new ArrayList<>();
        final Queue<Connection> connectionsReturned = new ArrayBlockingQueue<>(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final Thread thread = new Thread(() -> connectionsReturned.add(manager.waitForConnection()));
            threads.add(thread);
            thread.start();
        }

        Thread.sleep(10);
        assertEquals(0, connectionsReturned.size(), "all the threads should still be blocking");
        waitingForConnection.countDown();
        for (final Thread thread : threads) {
            thread.join();
        }
        assertEquals(numThreads, connectionsReturned.size(), "all the threads should have added a connection");
        for (final Connection c : connectionsReturned) {
            assertSame(connection, c, "all the threads should have received the same connection");
        }
    }
}
