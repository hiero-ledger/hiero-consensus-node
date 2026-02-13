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
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.consensus.crypto.SigningFactory;
import org.hiero.consensus.crypto.SigningImplementation;
import org.hiero.consensus.event.creator.config.EventCreationConfig;
import org.hiero.consensus.event.creator.config.EventCreationConfig_;
import org.hiero.consensus.event.creator.impl.DefaultEventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.gossip.impl.gossip.NoOpIntakeEventCounter;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;
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
 * Benchmark for measuring event creation throughput of {@link DefaultEventCreationManager} instances. This benchmark runs
 * multiple event creators in a single thread. Although this is not a completely accurate benchmark of a single event
 * creator, it should be a good approximation of throughput. The reason for using multiple event creators is that it
 * is not trivial to use just one, since it has to build on top of events created by other nodes.
 */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 3)
public class EventCreatorNetworkBenchmark {

    /** The number of nodes in the simulated network. */
    @Param({"4", "8"})
    public int numNodes;

    /** Random seed for reproducibility. */
    @Param({"0"})
    public long seed;

    @Param() // Empty means use all available types
    public SigningImplementation signingType;

    /** The event creators for each node in the network. */
    private List<DefaultEventCreationManager> eventCreators;

    /** The roster defining the network. */
    private Roster roster;

    /** Total number of events created in the current iteration. */
    private int eventsCreatedInIteration;

    /** Current event window for the network. */
    private EventWindow eventWindow;

    /** The number of events after which the event window should be updated */
    private long eventWindowUpdateInterval;

    /** Orphan buffer, required to set the nGen value needed by the event creator */
    private OrphanBuffer orphanBuffer;

    @Setup(Level.Trial)
    public void setupTrial() {
        // Build a roster with real keys
        final RandomRosterBuilder rosterBuilder = RandomRosterBuilder.create(Randotron.create(seed))
                .withSize(numNodes)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .withRealKeysEnabled(true);
        roster = rosterBuilder.build();
        eventWindowUpdateInterval = Math.round(numNodes * Math.log(numNodes));
    }

    @Setup(Level.Iteration)
    public void setupIteration() throws NoSuchAlgorithmException, NoSuchProviderException {
        eventCreators = new ArrayList<>(numNodes);
        eventWindow = EventWindow.getGenesisEventWindow();
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
            final SecureRandom nodeRandom = new SecureRandom();
            nodeRandom.setSeed(nodeId.id());
            final KeyPair keyPair = SigningFactory.generateKeyPair(signingType.getSigningSchema(), nodeRandom);
            final BytesSigner signer = SigningFactory.createSigner(signingType, keyPair);
            final EventCreator eventCreator =
                    new TipsetEventCreator(configuration, metrics, time, nodeRandom, signer, roster, nodeId, List::of);

            final DefaultEventCreationManager eventCreationManager = new DefaultEventCreationManager(
                    configuration, metrics, time, () -> false, eventCreator, roster, nodeId);

            // Set platform status to ACTIVE so events can be created
            eventCreationManager.updatePlatformStatus(PlatformStatus.ACTIVE);
            eventCreationManager.setEventWindow(eventWindow);

            eventCreators.add(eventCreationManager);
        }
        orphanBuffer = new DefaultOrphanBuffer(metrics, new NoOpIntakeEventCounter());

        eventsCreatedInIteration = 0;
    }

    /**
     * Benchmark that measures event creation throughput.
     * <p>
     * In each iteration:
     * <ol>
     *   <li>Each node attempts to create an event, until one is created</li>
     *   <li>Successfully created events are shared with all other nodes</li>
     * </ol>
     * <p>
     *
     * @param bh JMH blackhole to prevent dead code elimination
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void networkEventCreation(final Blackhole bh) {
        /*
        Results from a run on a 2020 M1 MacBook Pro:

        Benchmark                                          (numNodes)  (seed)   (signingType)   Mode  Cnt      Score   Error  Units
        EventCreatorNetworkBenchmark.networkEventCreation           4       0          RSA_BC  thrpt    2    339.674          ops/s
        EventCreatorNetworkBenchmark.networkEventCreation           4       0         RSA_JDK  thrpt    2    354.803          ops/s
        EventCreatorNetworkBenchmark.networkEventCreation           4       0          EC_JDK  thrpt    2   1218.762          ops/s
        EventCreatorNetworkBenchmark.networkEventCreation           4       0  ED25519_SODIUM  thrpt    2  52464.443          ops/s
        EventCreatorNetworkBenchmark.networkEventCreation           4       0     ED25519_SUN  thrpt    2   2166.134          ops/s
        EventCreatorNetworkBenchmark.networkEventCreation           8       0          RSA_BC  thrpt    2    342.977          ops/s
        EventCreatorNetworkBenchmark.networkEventCreation           8       0         RSA_JDK  thrpt    2    355.044          ops/s
        EventCreatorNetworkBenchmark.networkEventCreation           8       0          EC_JDK  thrpt    2   1216.903          ops/s
        EventCreatorNetworkBenchmark.networkEventCreation           8       0  ED25519_SODIUM  thrpt    2  45992.627          ops/s
        EventCreatorNetworkBenchmark.networkEventCreation           8       0     ED25519_SUN  thrpt    2   2153.820          ops/s
        */
        PlatformEvent newEvent = null;
        for (final DefaultEventCreationManager creator : eventCreators) {
            final PlatformEvent event = creator.maybeCreateEvent();
            if (event != null) {
                newEvent = event;
                bh.consume(event);
                break;
            }
        }
        if (newEvent == null) {
            throw new RuntimeException("At least one creator should always be able to create an event");
        }
        final List<PlatformEvent> unorphanedEvents = orphanBuffer.handleEvent(newEvent);
        if (unorphanedEvents.size() != 1) {
            throw new RuntimeException("There should be no orphaned events in this benchmark");
        }

        // Share newly created events with all nodes (simulating gossip)
        for (final DefaultEventCreationManager creator : eventCreators) {
            creator.registerEvent(newEvent);
        }

        eventsCreatedInIteration++;

        // Periodically update event window to simulate consensus progress
        if (eventsCreatedInIteration % eventWindowUpdateInterval == 0) {
            eventWindow = new EventWindow(
                    eventWindow.latestConsensusRound() + 1,
                    eventWindow.newEventBirthRound() + 1,
                    Math.max(1, eventWindow.latestConsensusRound() - 25),
                    Math.max(1, eventWindow.latestConsensusRound() - 25));

            for (final DefaultEventCreationManager creator : eventCreators) {
                creator.setEventWindow(eventWindow);
            }
            eventsCreatedInIteration = 0;
        }
    }
}
