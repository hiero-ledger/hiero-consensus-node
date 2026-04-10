// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.jmh;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.consensus.crypto.SigningFactory;
import org.hiero.consensus.crypto.SigningImplementation;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.event.creator.config.EventCreationConfig;
import org.hiero.consensus.event.creator.config.EventCreationConfig_;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * A JMH benchmark that measures the throughput of {@link TipsetEventCreator#registerEvent(PlatformEvent)}.
 */
@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
public class EventCreatorRegisterBenchmark {

    private static final SigningImplementation SIGNING_TYPE = SigningImplementation.ED25519_SODIUM;

    /**
     * Number of peer events pre-generated per batch and registered per benchmark invocation.
     * At ~14 μs per event creation (ED25519_SODIUM), generating 1000 events takes ~14 ms.
     */
    private static final int BATCH_SIZE = 1000;

    /** The number of nodes in the simulated network. */
    @Param({"4", "40"})
    public int numNodes;

    /** Random seed for reproducibility. */
    @Param({"0"})
    public long seed;

    /** The event creator under test (node 0). */
    private TipsetEventCreator selfCreator;

    /** All event creators in the simulated network, indexed by roster order. */
    private List<TipsetEventCreator> allCreators;

    /** The roster defining the network topology. */
    private Roster roster;

    /** Orphan buffer for assigning nGen values to created events. */
    private OrphanBuffer orphanBuffer;

    /** Current event window for the network. */
    private EventWindow eventWindow;

    /** Total events created since the last event window update. */
    private int eventsCreated;

    /** Number of events between event window advances. */
    private long eventWindowUpdateInterval;

    /** Rotating index into peer creators (1..numNodes-1) for round-robin parent generation. */
    private int nextPeerIndex;

    /** Pre-generated batch of peer events to be registered on the self creator. */
    private PlatformEvent[] eventBatch;

    /**
     * Builds the roster once per trial.
     */
    @Setup(Level.Trial)
    public void setupTrial() {
        roster = RandomRosterBuilder.create(Randotron.create(seed))
                .withSize(numNodes)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .withRealKeysEnabled(true)
                .build();
        eventWindowUpdateInterval = Math.max(1, Math.round(numNodes * Math.log(numNodes)));
    }

    /**
     * Creates fresh {@link TipsetEventCreator} instances, bootstraps the network with genesis events,
     * and pre-generates a batch of peer events for the benchmark to register on the self creator.
     */
    @Setup(Level.Iteration)
    public void setupIteration() {
        eventWindow = EventWindow.getGenesisEventWindow();
        eventsCreated = 0;
        nextPeerIndex = 1;

        final Configuration configuration = new TestConfigBuilder()
                .withConfigDataType(EventCreationConfig.class)
                .withValue(EventCreationConfig_.MAX_CREATION_RATE, 0)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();
        final Metrics metrics = platformContext.getMetrics();
        final Time time = platformContext.getTime();

        allCreators = new ArrayList<>(numNodes);
        for (final RosterEntry entry : roster.rosterEntries()) {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            final SecureRandom nodeRandom = new SecureRandom();
            nodeRandom.setSeed(nodeId.id());
            final KeyPair keyPair = SigningFactory.generateKeyPair(SIGNING_TYPE.getSigningSchema(), nodeRandom);
            final BytesSigner signer = SigningFactory.createSigner(SIGNING_TYPE, keyPair);
            final TipsetEventCreator creator =
                    new TipsetEventCreator(configuration, metrics, time, nodeRandom, signer, roster, nodeId, List::of);
            creator.setEventWindow(eventWindow);
            allCreators.add(creator);
        }
        selfCreator = allCreators.getFirst();
        orphanBuffer = new DefaultOrphanBuffer(metrics, new NoOpIntakeEventCounter());

        // Bootstrap: each node creates a genesis event and distributes it to all others
        for (final TipsetEventCreator creator : allCreators) {
            final PlatformEvent genesis = creator.maybeCreateEvent();
            if (genesis != null) {
                processAndDistribute(genesis);
            }
        }

        // Pre-generate a batch of peer events, distributed to peers but NOT to self
        eventBatch = new PlatformEvent[BATCH_SIZE];
        int generated = 0;
        while (generated < BATCH_SIZE) {
            final TipsetEventCreator peer = allCreators.get(nextPeerIndex);
            advancePeerIndex();

            final PlatformEvent peerEvent = peer.maybeCreateEvent();
            if (peerEvent != null) {
                orphanBuffer.handleEvent(peerEvent);
                // Distribute to all creators except self (index 0)
                for (int i = 1; i < allCreators.size(); i++) {
                    allCreators.get(i).registerEvent(peerEvent);
                }
                eventsCreated++;
                eventBatch[generated++] = peerEvent;

                // Advance the event window periodically to prevent unbounded state growth
                if (eventsCreated % eventWindowUpdateInterval == 0) {
                    eventWindow = new EventWindow(
                            eventWindow.latestConsensusRound() + 1,
                            eventWindow.newEventBirthRound() + 1,
                            Math.max(1, eventWindow.latestConsensusRound() - 25),
                            Math.max(1, eventWindow.latestConsensusRound() - 25));
                    for (final TipsetEventCreator creator : allCreators) {
                        creator.setEventWindow(eventWindow);
                    }
                }
            }
        }
    }

    /**
     * Measures the throughput of {@link TipsetEventCreator#registerEvent(PlatformEvent)} by registering
     * a pre-generated batch of peer events on the self creator. Each invocation registers
     * {@link #BATCH_SIZE} events; JMH divides by that number to report per-event throughput.
     */
    /*
    Results on a M3 Max MacBook Pro:

    Benchmark                                    (numNodes)  (seed)   Mode  Cnt         Score         Error  Units
    EventCreatorRegisterBenchmark.registerEvent           4       0  thrpt    5  30285143.899 ± 1090695.300  ops/s
    EventCreatorRegisterBenchmark.registerEvent          40       0  thrpt    5   1777030.854 ±   20626.328  ops/s
    */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(BATCH_SIZE)
    public void registerEvent() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            selfCreator.registerEvent(eventBatch[i]);
        }
    }

    /**
     * Processes an event through the orphan buffer (to assign nGen) and distributes it to all creators.
     */
    private void processAndDistribute(final PlatformEvent event) {
        orphanBuffer.handleEvent(event);
        for (final TipsetEventCreator creator : allCreators) {
            creator.registerEvent(event);
        }
        eventsCreated++;
    }

    /**
     * Advances the peer index in round-robin fashion, skipping index 0 (self).
     */
    private void advancePeerIndex() {
        nextPeerIndex++;
        if (nextPeerIndex >= numNodes) {
            nextPeerIndex = 1;
        }
    }
}
