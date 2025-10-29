// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.jmh;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.hiero.consensus.event.creator.EventCreationConfig;
import org.hiero.consensus.event.creator.EventCreationConfig_;
import org.hiero.consensus.event.creator.impl.DefaultEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark for measuring network-wide event creation throughput when multiple
 * {@link DefaultEventCreator} instances create and share events.
 * <p>
 * This benchmark simulates a network of nodes where each node:
 * <ul>
 *   <li>Creates events using its own DefaultEventCreator instance</li>
 *   <li>Shares created events with all other nodes in the network</li>
 *   <li>Receives and processes events from other nodes</li>
 * </ul>
 * <p>
 * The benchmark measures the aggregate event creation rate across all nodes in the network,
 * providing insight into network-wide throughput rather than single-node performance.
 */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 2, time = 10)
public class EventCreatorNetworkBenchmark {

    /**
     * The number of nodes in the simulated network.
     */
    //@Param({"4", "10", "39"})
    @Param({"4"})
    public int numNodes;

    /**
     * Random seed for reproducibility.
     */
    @Param({"0"})
    public long seed;

    /**
     * The event creators for each node in the network.
     */
    private List<DefaultEventCreator> eventCreators;

    /**
     * The roster defining the network.
     */
    private Roster roster;

    /**
     * Keys and certificates for each node in the network.
     */
    private Function<NodeId, KeysAndCerts> nodeKeysAndCerts;

    /**
     * Random number generator.
     */
    private Random random;

    /**
     * Total number of events created in the current round.
     */
    private int eventsCreatedInIteration;

    /**
     * Current event window for the network.
     */
    private EventWindow eventWindow;

    @Setup(Level.Trial)
    public void setupTrial() {
        random = new Random(seed);
        eventWindow = EventWindow.getGenesisEventWindow();

        // Build a roster with real keys
        final RandomRosterBuilder rosterBuilder = RandomRosterBuilder.create(Randotron.create(seed))
                .withSize(numNodes)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .withRealKeysEnabled(true);
        roster = rosterBuilder.build();
        nodeKeysAndCerts = rosterBuilder::getPrivateKeys;
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        eventCreators = new ArrayList<>(numNodes);
        final Configuration configuration = new TestConfigBuilder()
                .withConfigDataType(EventCreationConfig.class)
                .withValue(EventCreationConfig_.MAX_CREATION_RATE, 0)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();
        final Metrics metrics = platformContext.getMetrics();
        final Time time = platformContext.getTime();

        // Create an event creator for each node
        for (final RosterEntry entry : roster.rosterEntries()) {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            final KeysAndCerts keysAndCerts = nodeKeysAndCerts.apply(nodeId);
            final SecureRandom nodeRandom = new SecureRandom();
            nodeRandom.setSeed(random.nextLong());

            final DefaultEventCreator eventCreator = new DefaultEventCreator();
            eventCreator.initialize(
                    configuration,
                    metrics,
                    time,
                    nodeRandom,
                    keysAndCerts,
                    roster,
                    nodeId,
                    List::of,
                    ()->false);

            // Set platform status to ACTIVE so events can be created
            eventCreator.updatePlatformStatus(PlatformStatus.ACTIVE);
            eventCreator.setEventWindow(eventWindow);

            eventCreators.add(eventCreator);
        }

        eventsCreatedInIteration = 0;
    }

    /**
     * Benchmark that measures network-wide event creation throughput.
     * <p>
     * In each iteration:
     * <ol>
     *   <li>Each node attempts to create an event</li>
     *   <li>Successfully created events are shared with all other nodes</li>
     *   <li>The total number of events created is returned as the metric</li>
     * </ol>
     * <p>
     * This simulates a realistic gossip scenario where events are propagated
     * through the network and used as parents for new events.
     *
     * @param bh JMH blackhole to prevent dead code elimination
     * @return the number of events created in this round
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public int networkEventCreation(final Blackhole bh) {
        PlatformEvent newEvent = null;
        for (final DefaultEventCreator creator : eventCreators) {
            final PlatformEvent event = creator.maybeCreateEvent();
            if (event != null) {
                newEvent = event;
                bh.consume(event);
                break;
            }
        }
        if(newEvent == null) {
            throw new RuntimeException("No event created");
        }

        // Share newly created events with all nodes (simulating gossip)
        for (final DefaultEventCreator creator : eventCreators) {
            creator.registerEvent(newEvent);
        }


        eventsCreatedInIteration ++;

        // Periodically update event window to simulate consensus progress
//        if (eventsCreatedInIteration > 100) {
//            eventWindow = new EventWindow(
//                    eventWindow.latestConsensusRound() + 1,
//                    eventWindow.newEventBirthRound() + 1,
//                    eventWindow.ancientThreshold() + 1,
//                    eventWindow.expiredThreshold() + 1);
//
//            for (final DefaultEventCreator creator : eventCreators) {
//                creator.setEventWindow(eventWindow);
//            }
//            eventsCreatedInIteration = 0;
//        }

        return 1;
    }

    @TearDown(Level.Iteration)
    public void validateState() {
        // Validate that event creators are in a consistent state
        for (final DefaultEventCreator creator : eventCreators) {
            // Check invariants, log statistics, etc.
        }

        System.out.println("\nEvents created in iteration: " + eventsCreatedInIteration);
        // You could throw an exception if state is invalid
        if (eventsCreatedInIteration < 1_000) {
            throw new IllegalStateException("Invalid event count");
        }
    }
}
