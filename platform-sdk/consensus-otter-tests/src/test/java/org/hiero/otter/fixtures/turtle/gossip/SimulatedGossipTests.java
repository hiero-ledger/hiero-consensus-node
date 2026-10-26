// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.gossip;

import static org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_THREADSAFE_CONFIGURATION;
import static org.hiero.otter.fixtures.network.BandwidthLimit.UNLIMITED_BANDWIDTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.gossip.impl.gossip.sync.protocol.SyncStatusChecker;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.roster.test.fixtures.RosterFactory;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.wiring.framework.model.TraceableWiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.schedulers.ExceptionHandlers;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.wires.input.BindableInputWire;
import org.hiero.consensus.wiring.framework.wires.output.StandardOutputWire;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class SimulatedGossipTests {

    /**
     * Given a list of events that may contain duplicates, create a set of unique event descriptors.
     *
     * @param events the list of events
     * @return the set of unique event descriptors
     */
    private static Set<EventDescriptorWrapper> getUniqueDescriptors(@NonNull final List<PlatformEvent> events) {
        final HashSet<EventDescriptorWrapper> uniqueDescriptors = new HashSet<>();
        for (final PlatformEvent event : events) {
            uniqueDescriptors.add(event.getDescriptor());
        }
        return uniqueDescriptors;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16, 32})
    void randomDataTest(final int networkSize) {
        final Randotron randotron = Randotron.create();

        final FakeTime time = new FakeTime();

        final Roster roster = RosterFactory.randomRoster(randotron, networkSize);
        final List<NodeId> nodeIds = roster.rosterEntries().stream()
                .map(RosterUtils::getNodeId)
                .sorted()
                .toList();

        final SimulatedNetworkConnectivity network = new SimulatedNetworkConnectivity(randotron, time.now());

        // We can safely choose large numbers because time is simulated
        final Duration averageDelay = Duration.ofMillis(randotron.nextInt(1, 1_000_000));
        final ConnectionState connectionState =
                new ConnectionState(true, averageDelay, Percentage.withPercentage(10.0), UNLIMITED_BANDWIDTH);
        final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();
        for (final NodeId sender : nodeIds) {
            for (final NodeId receiver : nodeIds) {
                if (!sender.equals(receiver)) {
                    final NodeId fromNode = NodeId.of(sender.id());
                    final NodeId toNode = NodeId.of(receiver.id());
                    connections.put(new ConnectionKey(fromNode, toNode), connectionState);
                }
            }
        }
        network.setConnections(connections);

        // Each node will add received events to the appropriate list
        final Map<NodeId, List<PlatformEvent>> receivedEvents = new HashMap<>();

        // Passing an event to one of these consumers causes the node to gossip the event to the network
        final Map<NodeId, Consumer<PlatformEvent>> eventSubmitters = new HashMap<>();

        // Wire things up
        for (final NodeId nodeId : nodeIds) {
            final List<PlatformEvent> receivedEventsForNode = new ArrayList<>();
            receivedEvents.put(nodeId, receivedEventsForNode);
            eventSubmitters.put(
                    nodeId,
                    addNode(network, nodeId, PlatformStatus.ACTIVE, receivedEventsForNode, new ArrayList<>())
                            .eventSubmitter());
        }

        // For each event, choose a random subset of nodes that will submit the event. Our end goal is to see
        // each event distributed at least once to each node in the network.
        final int eventCount = networkSize * 100;
        final List<PlatformEvent> eventsToGossip = new ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            final NodeId creator = nodeIds.get(randotron.nextInt(networkSize));
            final PlatformEvent event =
                    new TestingEventBuilder(randotron).setCreatorId(creator).build();
            new DefaultEventHasher().hashEvent(event);

            eventsToGossip.add(event);
        }

        // Gossip all of the events. Each event is guaranteed to be gossiped by at least one node.
        for (int eventIndex = 0; eventIndex < eventCount; eventIndex++) {
            final PlatformEvent event = eventsToGossip.get(eventIndex);

            for (final NodeId nodeId : nodeIds) {
                if (event.getCreatorId().equals(nodeId) || randotron.nextBoolean(0.1)) {
                    eventSubmitters.get(nodeId).accept(event);

                    // When a node sends out an event, add it to the list of events that node knows about.
                    receivedEvents.get(nodeId).add(event);
                }
            }

            if (randotron.nextBoolean(0.1)) {
                time.tick(averageDelay.dividedBy(10));
                network.tick(time.now());
            }
        }

        // Move time forward enough to ensure that all events have been gossiped around the network.
        // Ticking twice ensures everything is properly flushed out of the system.
        network.tick(time.now());
        time.tick(averageDelay.multipliedBy(100000));
        network.tick(time.now());

        // Verify that all nodes received all events.
        final Set<EventDescriptorWrapper> expectedDescriptors = getUniqueDescriptors(eventsToGossip);
        for (final NodeId nodeId : nodeIds) {
            final Set<EventDescriptorWrapper> uniqueDescriptors = getUniqueDescriptors(receivedEvents.get(nodeId));
            assertEquals(expectedDescriptors, uniqueDescriptors);
        }
    }

    /**
     * Receiving must be refused in exactly the statuses that don't permit syncing, while submissions to the network
     * are unaffected by the status.
     */
    @ParameterizedTest
    @EnumSource(PlatformStatus.class)
    void statusGatesReceivingButNotSubmitting(final PlatformStatus status) {
        final Randotron randotron = Randotron.create();
        final FakeTime time = new FakeTime();
        final NodeId selfId = NodeId.of(0);
        final NodeId peerId = NodeId.of(1);

        final SimulatedNetworkConnectivity network = new SimulatedNetworkConnectivity(randotron, time.now());
        final ConnectionState connectionState =
                new ConnectionState(true, Duration.ofMillis(1), Percentage.withPercentage(0.0), UNLIMITED_BANDWIDTH);
        network.setConnections(Map.of(
                new ConnectionKey(selfId, peerId), connectionState,
                new ConnectionKey(peerId, selfId), connectionState));

        final List<PlatformEvent> selfReceived = new ArrayList<>();
        final List<PlatformEvent> peerReceived = new ArrayList<>();
        final TestNode self = addNode(network, selfId, status, selfReceived, new ArrayList<>());
        final TestNode peer = addNode(network, peerId, PlatformStatus.ACTIVE, peerReceived, new ArrayList<>());

        // submissions are unaffected by the status of the submitter
        final PlatformEvent submitted =
                new TestingEventBuilder(randotron).setCreatorId(selfId).build();
        new DefaultEventHasher().hashEvent(submitted);
        self.eventSubmitter().accept(submitted);

        // an event from the peer is only delivered if the status permits syncing
        final PlatformEvent fromPeer =
                new TestingEventBuilder(randotron).setCreatorId(peerId).build();
        new DefaultEventHasher().hashEvent(fromPeer);
        peer.eventSubmitter().accept(fromPeer);

        network.tick(time.now());
        time.tick(Duration.ofSeconds(1));
        network.tick(time.now());

        final boolean permitted = SyncStatusChecker.doesStatusPermitSync(status);
        assertEquals(1, peerReceived.size());
        assertEquals(permitted ? 1 : 0, selfReceived.size());

        assertEquals(permitted, self.gossip().receiveEvent(fromPeer));
        assertEquals(permitted, self.gossip().receiveEventWindow(peerId, EventWindow.getGenesisEventWindow()));
        if (permitted) {
            assertTrue(selfReceived.contains(fromPeer));
        } else {
            assertFalse(selfReceived.contains(fromPeer));
        }
    }

    @Test
    void peerWindowProducesSyncProgress() {
        final SimulatedNetworkConnectivity network =
                new SimulatedNetworkConnectivity(Randotron.create(), Instant.EPOCH);
        final NodeId self = NodeId.of(0);
        final NodeId peer = NodeId.of(1);
        final List<SyncProgress> progress = new ArrayList<>();
        final SimulatedGossip gossip = addNode(network, self, PlatformStatus.ACTIVE, new ArrayList<>(), progress)
                .gossip();

        final EventWindow peerWindow = new EventWindow(30, 31, 5, 1);
        assertTrue(gossip.receiveEventWindow(peer, peerWindow));

        assertEquals(List.of(new SyncProgress(peer, EventWindow.getGenesisEventWindow(), peerWindow)), progress);
    }

    @Test
    void ownWindowChangeProducesSyncProgressForEveryPeer() {
        final SimulatedNetworkConnectivity network =
                new SimulatedNetworkConnectivity(Randotron.create(), Instant.EPOCH);
        final NodeId self = NodeId.of(0);
        final NodeId peerA = NodeId.of(1);
        final NodeId peerB = NodeId.of(2);
        final List<SyncProgress> progress = new ArrayList<>();
        final TestNode node = addNode(network, self, PlatformStatus.ACTIVE, new ArrayList<>(), progress);
        final SimulatedGossip gossip = node.gossip();

        final EventWindow windowA1 = new EventWindow(30, 31, 5, 1);
        final EventWindow windowA2 = new EventWindow(32, 33, 7, 1);
        final EventWindow windowB = new EventWindow(40, 41, 15, 1);
        gossip.receiveEventWindow(peerA, windowA1);
        gossip.receiveEventWindow(peerB, windowB);
        gossip.receiveEventWindow(peerA, windowA2);
        progress.clear();

        final EventWindow ownWindow = new EventWindow(10, 11, 1, 1);
        node.eventWindowSubmitter().accept(ownWindow);

        assertEquals(2, progress.size());
        assertEquals(
                Set.of(new SyncProgress(peerA, ownWindow, windowA2), new SyncProgress(peerB, ownWindow, windowB)),
                Set.copyOf(progress));
    }

    /**
     * A node of the simulated network.
     *
     * @param gossip the gossip instance of the node
     * @param eventSubmitter passing an event to it makes the node gossip the event to the network
     * @param eventWindowSubmitter passing an event window to it changes the node's own event window
     */
    private record TestNode(
            @NonNull SimulatedGossip gossip,
            @NonNull Consumer<PlatformEvent> eventSubmitter,
            @NonNull Consumer<EventWindow> eventWindowSubmitter) {}

    /**
     * Creates a node with a gossip instance in the given status, and adds it to the network.
     *
     * @param network the network to add the node to
     * @param nodeId the ID of the node
     * @param status the platform status the node is in
     * @param received collects the events the gossip instance forwards
     * @param syncProgress collects the sync progress the gossip instance reports
     * @return the created node
     */
    @SuppressWarnings("unchecked")
    private static TestNode addNode(
            @NonNull final SimulatedNetworkConnectivity network,
            @NonNull final NodeId nodeId,
            @NonNull final PlatformStatus status,
            @NonNull final List<PlatformEvent> received,
            @NonNull final List<SyncProgress> syncProgress) {
        final WiringModel model = WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent())
                .deterministic()
                .build();
        final TaskScheduler<Void> shim = model.<Void>schedulerBuilder("eventInputShim")
                .configure(DIRECT_THREADSAFE_CONFIGURATION)
                .build();
        final BindableInputWire<PlatformEvent, Void> eventInput = shim.buildInputWire("eventInputWire");
        final StandardOutputWire<PlatformEvent> output = new StandardOutputWire<>(
                (TraceableWiringModel) model, "eventOutputWire", ExceptionHandlers.RETHROW_UNCAUGHT_EXCEPTION);
        output.solderTo("handleOutputEvent", "event", received::add);
        final StandardOutputWire<SyncProgress> syncProgressOutput = new StandardOutputWire<>(
                (TraceableWiringModel) model, "syncProgressOutput", ExceptionHandlers.RETHROW_UNCAUGHT_EXCEPTION);
        syncProgressOutput.solderTo("syncProgressCollector", "syncProgress", syncProgress::add);

        // The gossip instance binds consumers to these; capture them to drive the instance from the test
        final BindableInputWire<EventWindow, Void> eventWindowInput = mock(BindableInputWire.class);
        final ArgumentCaptor<Consumer<EventWindow>> eventWindowCaptor = ArgumentCaptor.forClass(Consumer.class);
        doNothing().when(eventWindowInput).bindConsumer(eventWindowCaptor.capture());
        final BindableInputWire<PlatformStatus, Void> statusInput = mock(BindableInputWire.class);
        final ArgumentCaptor<Consumer<PlatformStatus>> statusCaptor = ArgumentCaptor.forClass(Consumer.class);
        doNothing().when(statusInput).bindConsumer(statusCaptor.capture());

        final SimulatedGossip gossip = new SimulatedGossip(network, nodeId);
        gossip.bind(
                model,
                eventInput,
                eventWindowInput,
                output,
                mock(BindableInputWire.class),
                mock(BindableInputWire.class),
                mock(BindableInputWire.class),
                mock(BindableInputWire.class),
                mock(BindableInputWire.class),
                mock(BindableInputWire.class),
                statusInput,
                syncProgressOutput);
        statusCaptor.getValue().accept(status);
        network.addNode(nodeId, gossip);
        model.start();
        return new TestNode(gossip, eventInput::inject, eventWindowCaptor.getValue());
    }
}
