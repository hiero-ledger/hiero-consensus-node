// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.gossip;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_THREADSAFE_CONFIGURATION;
import static org.hiero.otter.fixtures.network.utils.BandwidthLimit.UNLIMITED_BANDWIDTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.ExceptionHandlers;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16, 32})
    void randomDataTest(final int networkSize) {
        final Randotron randotron = Randotron.create();

        final FakeTime time = new FakeTime();

        final Roster roster =
                RandomRosterBuilder.create(randotron).withSize(networkSize).build();
        final List<NodeId> nodeIds = roster.rosterEntries().stream()
                .map(RosterUtils::getNodeId)
                .sorted()
                .toList();

        final SimulatedNetwork network = new SimulatedNetwork(randotron);
        nodeIds.forEach(network::addNode);

        // We can safely choose large numbers because time is simulated
        final Duration averageDelay = Duration.ofMillis(randotron.nextInt(1, 1_000_000));
        final ConnectionData connectionData =
                new ConnectionData(true, averageDelay, Percentage.withPercentage(10.0), UNLIMITED_BANDWIDTH);
        final Map<ConnectionKey, ConnectionData> connections = new HashMap<>();
        for (final NodeId sender : nodeIds) {
            for (final NodeId receiver : nodeIds) {
                if (!sender.equals(receiver)) {
                    final NodeId fromNode = NodeId.of(sender.id());
                    final NodeId toNode = NodeId.of(receiver.id());
                    connections.put(new ConnectionKey(fromNode, toNode), connectionData);
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
            final WiringModel model = WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent())
                    .withDeterministicModeEnabled(true)
                    .build();

            final TaskScheduler<Void> eventInputShim = model.<Void>schedulerBuilder("eventInputShim")
                    .configure(DIRECT_THREADSAFE_CONFIGURATION)
                    .build();

            final List<PlatformEvent> receivedEventsForNode = new ArrayList<>();
            receivedEvents.put(nodeId, receivedEventsForNode);
            final StandardOutputWire<PlatformEvent> eventOutputWire = new StandardOutputWire<>(
                    (TraceableWiringModel) model, "eventOutputWire", ExceptionHandlers.RETHROW_UNCAUGHT_EXCEPTION);
            eventOutputWire.solderTo("handleOutputEvent", "event", receivedEventsForNode::add);

            final BindableInputWire<PlatformEvent, Void> eventInputWire =
                    eventInputShim.buildInputWire("eventInputWire");
            eventSubmitters.put(nodeId, eventInputWire::inject);

            network.getGossipInstance(nodeId)
                    .bind(
                            model,
                            eventInputWire,
                            mock(BindableInputWire.class),
                            eventOutputWire,
                            mock(BindableInputWire.class),
                            mock(BindableInputWire.class),
                            mock(BindableInputWire.class),
                            mock(BindableInputWire.class),
                            mock(BindableInputWire.class),
                            mock(StandardOutputWire.class));
            model.start();
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
}
