// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.jmh;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.crypto.KeyGeneratingException;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.event.creator.impl.DefaultEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.consensus.roster.RosterRetriever;
import org.hiero.consensus.roster.RosterUtils;
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
@Warmup(iterations = 1, time = 2)
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
    private Map<NodeId, KeysAndCerts> nodeKeysAndCerts;

    /**
     * Random number generator.
     */
    private Random random;

    /**
     * Total number of events created in the current round.
     */
    private int eventsCreatedInRound;

    /**
     * Current event window for the network.
     */
    private EventWindow eventWindow;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        random = new Random(seed);
        eventWindow = EventWindow.getGenesisEventWindow();

        // Build a roster with real keys
        final RandomRosterBuilder rosterBuilder = RandomRosterBuilder.create(Randotron.create(seed))
                .withSize(numNodes)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .withRealKeysEnabled(true);
        final Roster tempRoster = rosterBuilder.build();

        // Convert to AddressBook and generate keys
        AddressBook addressBook = RosterUtils.buildAddressBook(tempRoster);
        for (int i = 0; i < addressBook.getSize(); i++) {
            final NodeId nodeId = addressBook.getNodeId(i);
            addressBook.add(addressBook
                    .getAddress(nodeId)
                    .copySetSelfName(RosterUtils.formatNodeName(nodeId.id()))
                    .copySetHostnameInternal("127.0.0.1"));
        }

        // Generate keys for all nodes
        final Map<NodeId, KeysAndCerts> keysAndCertsMap = CryptoStatic.generateKeysAndCerts(addressBook);
        roster = RosterRetriever.buildRoster(addressBook);
        nodeKeysAndCerts = keysAndCertsMap;
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        eventCreators = new ArrayList<>(numNodes);
        final PlatformContext platformContext = TestPlatformContextBuilder.create().build();
        final Configuration configuration = platformContext.getConfiguration();
        final Metrics metrics = platformContext.getMetrics();
        final Time time = platformContext.getTime();

        // Create an event creator for each node
        for (int i = 0; i < numNodes; i++) {
            final NodeId nodeId = NodeId.of(i);
            final KeysAndCerts keysAndCerts = nodeKeysAndCerts.get(nodeId);
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

        eventsCreatedInRound = 0;
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
        final List<PlatformEvent> newEvents = new ArrayList<>();

        // Each node attempts to create an event
        for (final DefaultEventCreator creator : eventCreators) {
            final PlatformEvent event = creator.maybeCreateEvent();
            if (event != null) {
                newEvents.add(event);
                bh.consume(event);
            }
        }

        // Share newly created events with all nodes (simulating gossip)
        for (final PlatformEvent event : newEvents) {
            for (final DefaultEventCreator creator : eventCreators) {
                creator.registerEvent(event);
            }
        }

        eventsCreatedInRound += newEvents.size();

        // Periodically update event window to simulate consensus progress
        if (eventsCreatedInRound > 100) {
            eventWindow = new EventWindow(
                    eventWindow.latestConsensusRound() + 1,
                    eventWindow.newEventBirthRound() + 1,
                    eventWindow.ancientThreshold() + 1,
                    eventWindow.expiredThreshold() + 1);

            for (final DefaultEventCreator creator : eventCreators) {
                creator.setEventWindow(eventWindow);
            }
            eventsCreatedInRound = 0;
        }

        return newEvents.size();
    }
}
