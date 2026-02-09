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
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * A JMH benchmark that measures the latency of a single {@link TipsetEventCreator#maybeCreateEvent()} call.
 */
@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
public class EventCreatorLatencyBenchmark {

    /** The number of nodes in the simulated network. */
    @Param({"4", "40"})
    public int numNodes;

    /** Random seed for reproducibility. */
    @Param({"0"})
    public long seed;

    /** The signing implementation to benchmark. Empty {@code @Param} auto-discovers all enum values. */
    @Param()
    public SigningImplementation signingType;

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

    /** Holds the self event from the previous benchmark invocation, distributed in the next setup. */
    private PlatformEvent pendingSelfEvent;

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
     * Creates fresh {@link TipsetEventCreator} instances for each node and bootstraps the network with genesis events.
     * This runs once per measurement iteration, giving a clean slate for each set of samples.
     */
    @Setup(Level.Iteration)
    public void setupIteration() {
        eventWindow = EventWindow.getGenesisEventWindow();
        eventsCreated = 0;
        nextPeerIndex = 1;
        pendingSelfEvent = null;

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
            final KeyPair keyPair = SigningFactory.generateKeyPair(signingType.getSigningSchema(), nodeRandom);
            final BytesSigner signer = SigningFactory.createSigner(signingType, keyPair);
            final TipsetEventCreator creator =
                    new TipsetEventCreator(configuration, metrics, time, nodeRandom, signer, roster, nodeId, List::of);
            creator.setEventWindow(eventWindow);
            allCreators.add(creator);
        }
        selfCreator = allCreators.get(0);
        orphanBuffer = new DefaultOrphanBuffer(metrics, new NoOpIntakeEventCounter());

        // Bootstrap: each node creates a genesis event and distributes it to all others
        for (final TipsetEventCreator creator : allCreators) {
            final PlatformEvent genesis = creator.maybeCreateEvent();
            if (genesis != null) {
                processAndDistribute(genesis);
            }
        }
    }

    /**
     * Prepares the state for the next benchmark invocation. This method is <b>not</b> included in JMH's
     * latency measurement.
     * <p>
     * It distributes the self event created in the previous invocation, then has one peer create and
     * distribute an event to ensure the self node has a fresh other-parent available.
     */
    @Setup(Level.Invocation)
    public void prepareParents() {
        // Distribute the self event from the previous benchmark invocation
        if (pendingSelfEvent != null) {
            processAndDistribute(pendingSelfEvent);
            pendingSelfEvent = null;
        }

        // Have one peer create an event and distribute it, providing fresh parents for self.
        // Try each peer in round-robin order until one succeeds.
        for (int attempt = 0; attempt < numNodes - 1; attempt++) {
            final TipsetEventCreator peer = allCreators.get(nextPeerIndex);
            advancePeerIndex();

            final PlatformEvent peerEvent = peer.maybeCreateEvent();
            if (peerEvent != null) {
                processAndDistribute(peerEvent);
                break;
            }
        }

        // Advance the event window periodically to prevent unbounded state growth
        if (eventsCreated > 0 && eventsCreated % eventWindowUpdateInterval == 0) {
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

    /**
     * Measures the latency of a single {@link TipsetEventCreator#maybeCreateEvent()} call.
     * <p>
     * This includes the tipset parent selection algorithm, event assembly, hashing ({@code PbjStreamHasher}),
     * and cryptographic signing. It does <b>not</b> include event distribution or orphan buffer processing,
     * which are handled in the per-invocation setup/teardown.
     *
     * @return the created event (returned to prevent dead-code elimination)
     */
    /*
    Results on a M3 Max MacBook Pro:

    Benchmark                                         (numNodes)  (seed)   (signingType)    Mode     Cnt       Score   Error  Units
    EventCreatorLatencyBenchmark.createEvent                    4       0          RSA_BC  sample   12018    2077.224 ± 5.473  us/op
    EventCreatorLatencyBenchmark.createEvent:p0.50              4       0          RSA_BC  sample            2019.328          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.99              4       0          RSA_BC  sample            2514.944          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.999             4       0          RSA_BC  sample            3682.226          us/op
    EventCreatorLatencyBenchmark.createEvent                    4       0         RSA_JDK  sample   12806    1948.240 ± 3.484  us/op
    EventCreatorLatencyBenchmark.createEvent:p0.50              4       0         RSA_JDK  sample            1927.168          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.99              4       0         RSA_JDK  sample            2158.592          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.999             4       0         RSA_JDK  sample            3608.076          us/op
    EventCreatorLatencyBenchmark.createEvent                    4       0  ED25519_SODIUM  sample  769052      13.942 ± 0.762  us/op
    EventCreatorLatencyBenchmark.createEvent:p0.50              4       0  ED25519_SODIUM  sample              12.992          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.99              4       0  ED25519_SODIUM  sample              17.888          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.999             4       0  ED25519_SODIUM  sample              46.016          us/op
    EventCreatorLatencyBenchmark.createEvent                    4       0     ED25519_SUN  sample   66261     374.331 ± 0.817  us/op
    EventCreatorLatencyBenchmark.createEvent:p0.50              4       0     ED25519_SUN  sample             369.664          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.99              4       0     ED25519_SUN  sample             417.987          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.999             4       0     ED25519_SUN  sample             462.068          us/op
    EventCreatorLatencyBenchmark.createEvent                   40       0          RSA_BC  sample   12098    2020.314 ± 3.760  us/op
    EventCreatorLatencyBenchmark.createEvent:p0.50             40       0          RSA_BC  sample            2000.896          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.99             40       0          RSA_BC  sample            2232.320          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.999            40       0          RSA_BC  sample            4747.305          us/op
    EventCreatorLatencyBenchmark.createEvent                   40       0         RSA_JDK  sample   12596    1941.938 ± 3.514  us/op
    EventCreatorLatencyBenchmark.createEvent:p0.50             40       0         RSA_JDK  sample            1925.120          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.99             40       0         RSA_JDK  sample            2154.496          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.999            40       0         RSA_JDK  sample            4667.974          us/op
    EventCreatorLatencyBenchmark.createEvent                   40       0  ED25519_SODIUM  sample  526655      14.373 ± 0.007  us/op
    EventCreatorLatencyBenchmark.createEvent:p0.50             40       0  ED25519_SODIUM  sample              14.000          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.99             40       0  ED25519_SODIUM  sample              18.528          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.999            40       0  ED25519_SODIUM  sample              27.392          us/op
    EventCreatorLatencyBenchmark.createEvent                   40       0     ED25519_SUN  sample   60165     375.903 ± 1.119  us/op
    EventCreatorLatencyBenchmark.createEvent:p0.50             40       0     ED25519_SUN  sample             370.688          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.99             40       0     ED25519_SUN  sample             424.110          us/op
    EventCreatorLatencyBenchmark.createEvent:p0.999            40       0     ED25519_SUN  sample             477.611          us/op
    */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public PlatformEvent createEvent() {
        final PlatformEvent event = selfCreator.maybeCreateEvent();
        if (event == null) {
            throw new IllegalStateException("Self creator should always be able to create an event");
        }
        pendingSelfEvent = event;
        return event;
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
