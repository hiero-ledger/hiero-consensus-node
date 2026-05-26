// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.network.simulation.fixtures.NetworkLatency;
import org.hiero.consensus.network.simulation.fixtures.SimulatedBroadcast;
import org.junit.jupiter.api.Test;

class SimulatedBroadcastTest {

    private static final int NUM_NODES = 4;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void eventsAreDeliveredAfterLatencyExpires() {
        final SimulatedBroadcast network = new SimulatedBroadcast(START, NUM_NODES);
        final PlatformEvent event = buildEvent(NodeId.of(0));

        network.submitEvent(event);

        // With zero latency (default), events arrive at START. Tick to START to deliver them.
        network.tick(START);

        for (int i = 0; i < NUM_NODES; i++) {
            final NodeId nodeId = NodeId.of(i);
            final List<PlatformEvent> delivered = network.getDeliveredEvents(nodeId);
            if (nodeId.equals(event.getCreatorId())) {
                assertTrue(delivered.isEmpty(), "Creator should not receive its own event");
            } else {
                assertEquals(1, delivered.size(), "Node " + nodeId + " should receive exactly one event");
            }
        }
    }

    @Test
    void eventsAreNotDeliveredBeforeLatencyExpires() {
        final SimulatedBroadcast network = new SimulatedBroadcast(START, NUM_NODES);
        network.setLatency(NetworkLatency.uniformLatency(Duration.ofMillis(100), NUM_NODES));

        final PlatformEvent event = buildEvent(NodeId.of(0));
        network.submitEvent(event);

        // Tick to 50ms — events should not be delivered yet
        network.tick(START.plusMillis(50));

        for (int i = 0; i < NUM_NODES; i++) {
            final List<PlatformEvent> delivered = network.getDeliveredEvents(NodeId.of(i));
            assertTrue(delivered.isEmpty(), "Events should not be delivered before latency expires");
        }

        // Tick to 100ms — events should now be delivered
        network.tick(START.plusMillis(100));

        int receiverCount = 0;
        for (int i = 0; i < NUM_NODES; i++) {
            final NodeId nodeId = NodeId.of(i);
            final List<PlatformEvent> delivered = network.getDeliveredEvents(nodeId);
            if (!nodeId.equals(event.getCreatorId())) {
                assertEquals(1, delivered.size(), "Node " + nodeId + " should receive the event at latency boundary");
                receiverCount++;
            }
        }
        assertEquals(NUM_NODES - 1, receiverCount);
    }

    @Test
    void getDeliveredEventsDrainsTheQueue() {
        final SimulatedBroadcast network = new SimulatedBroadcast(START, NUM_NODES);
        final PlatformEvent event = buildEvent(NodeId.of(0));

        network.submitEvent(event);
        network.tick(START);

        final NodeId receiver = NodeId.of(1);
        final List<PlatformEvent> firstCall = network.getDeliveredEvents(receiver);
        assertEquals(1, firstCall.size(), "First call should return the delivered event");

        final List<PlatformEvent> secondCall = network.getDeliveredEvents(receiver);
        assertTrue(secondCall.isEmpty(), "Second call should return empty — events were already drained");
    }

    @Test
    void setLatenciesAppliesAsymmetricLatencies() {
        final int nodes = 3;
        final SimulatedBroadcast network = new SimulatedBroadcast(START, nodes);

        final int[][] latencies = new int[nodes][nodes];
        for (int i = 0; i < nodes; i++) {
            for (int j = 0; j < nodes; j++) {
                // Asymmetric: latency from i->j = (i+1)*(j+1) * 10ms
                latencies[i][j] = (i + 1) * (j + 1) * 10;
            }
        }
        network.setLatency(NetworkLatency.pingMatrix(latencies));

        // Event from node 0:
        //   latency to node 1 = 1*2*10 = 20ms
        //   latency to node 2 = 1*3*10 = 30ms
        final PlatformEvent event = buildEvent(NodeId.of(0));
        network.submitEvent(event);

        // At 20ms, node 1 should have the event but node 2 should not
        network.tick(START.plusMillis(20));
        assertEquals(1, network.getDeliveredEvents(NodeId.of(1)).size(), "Node 1 should receive at 20ms");
        assertTrue(network.getDeliveredEvents(NodeId.of(2)).isEmpty(), "Node 2 should not receive at 20ms");

        // At 30ms, node 2 should now have the event
        network.tick(START.plusMillis(30));
        assertEquals(1, network.getDeliveredEvents(NodeId.of(2)).size(), "Node 2 should receive at 30ms");
    }

    @Test
    void eventsFromMultipleCreatorsAreDelivered() {
        final SimulatedBroadcast network = new SimulatedBroadcast(START, NUM_NODES);

        // Submit one event per node
        for (int i = 0; i < NUM_NODES; i++) {
            network.submitEvent(buildEvent(NodeId.of(i)));
        }

        network.tick(START);

        // Each node should receive events from the other (NUM_NODES - 1) nodes
        for (int i = 0; i < NUM_NODES; i++) {
            final List<PlatformEvent> delivered = network.getDeliveredEvents(NodeId.of(i));
            assertEquals(
                    NUM_NODES - 1, delivered.size(), "Node " + i + " should receive one event from each other node");
        }
    }

    @Test
    void eventsAreDeliveredInArrivalTimeOrder() {
        final int nodes = 3;
        final SimulatedBroadcast network = new SimulatedBroadcast(START, nodes);

        // Configure so that node 1's events arrive at node 0 with 50ms latency,
        // and node 2's events arrive at node 0 with 20ms latency.
        final int[][] latencies = new int[nodes][nodes];
        latencies[1][0] = 50; // node 1 -> node 0: 50ms
        latencies[2][0] = 20; // node 2 -> node 0: 20ms
        network.setLatency(NetworkLatency.pingMatrix(latencies));

        // Submit event from node 1 first, then from node 2.
        // Despite node 1's event being submitted first, node 2's event should arrive first
        // because it has a shorter latency.
        final PlatformEvent eventFromNode1 = buildEvent(NodeId.of(1));
        final PlatformEvent eventFromNode2 = buildEvent(NodeId.of(2));
        network.submitEvent(eventFromNode1);
        network.submitEvent(eventFromNode2);

        // Tick past both delivery times
        network.tick(START.plusMillis(50));

        final List<PlatformEvent> deliveredToNode0 = network.getDeliveredEvents(NodeId.of(0));
        assertEquals(2, deliveredToNode0.size(), "Node 0 should receive both events");

        // Node 2's event (20ms latency) should arrive before node 1's event (50ms latency)
        assertEquals(
                NodeId.of(2),
                deliveredToNode0.get(0).getSenderId(),
                "First delivered event should be from node 2 (lower latency)");
        assertEquals(
                NodeId.of(1),
                deliveredToNode0.get(1).getSenderId(),
                "Second delivered event should be from node 1 (higher latency)");
    }

    @Test
    void eventsSubmittedAtDifferentTimesAreDeliveredInOrder() {
        final int nodes = 2;
        final SimulatedBroadcast network = new SimulatedBroadcast(START, nodes);
        network.setLatency(NetworkLatency.uniformLatency(Duration.ofMillis(100), nodes));

        // Submit first event at START
        final PlatformEvent firstEvent = buildEvent(NodeId.of(0));
        network.submitEvent(firstEvent); // arrives at START + 100ms

        // Advance to START + 50ms and submit second event
        network.tick(START.plusMillis(50));
        final PlatformEvent secondEvent = buildEvent(NodeId.of(0));
        network.submitEvent(secondEvent); // arrives at START + 150ms

        // Tick to START + 100ms — only first event should be delivered
        network.tick(START.plusMillis(100));
        final List<PlatformEvent> firstBatch = network.getDeliveredEvents(NodeId.of(1));
        assertEquals(1, firstBatch.size(), "Only the first event should be delivered at 100ms");

        // Tick to START + 150ms — second event should now be delivered
        network.tick(START.plusMillis(150));
        final List<PlatformEvent> secondBatch = network.getDeliveredEvents(NodeId.of(1));
        assertEquals(1, secondBatch.size(), "The second event should be delivered at 150ms");
    }

    /**
     * Builds a {@link PlatformEvent} with the given creator ID.
     */
    private static PlatformEvent buildEvent(final NodeId creatorId) {
        return new TestingEventBuilder(new Random(creatorId.id()))
                .setCreatorId(creatorId)
                .build();
    }
}
