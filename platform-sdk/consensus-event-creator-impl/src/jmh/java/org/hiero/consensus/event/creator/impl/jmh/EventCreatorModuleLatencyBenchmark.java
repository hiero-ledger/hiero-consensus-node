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
import org.hiero.consensus.event.creator.impl.DefaultEventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
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
 * A JMH benchmark that measures the latency of event creation through the complete
 * event creator module.
 */
@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
public class EventCreatorModuleLatencyBenchmark {

    /** The number of nodes in the simulated network. */
    @Param({"4", "40"})
    public int numNodes;

    /** Random seed for reproducibility. */
    @Param({"0"})
    public long seed;

    /** The signing implementation to benchmark. Empty {@code @Param} auto-discovers all enum values. */
    @Param()
    public SigningImplementation signingType;

    /** The event creation manager under test (node 0). */
    private DefaultEventCreationManager selfManager;

    /** All event creation managers in the simulated network, indexed by roster order. */
    private List<DefaultEventCreationManager> allManagers;

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

    /** Rotating index into peer managers (1..numNodes-1) for round-robin parent generation. */
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
     * Creates fresh {@link DefaultEventCreationManager} instances for each node and bootstraps
     * the network with genesis events. This runs once per measurement iteration, giving a clean
     * slate for each set of samples.
     */
    @Setup(Level.Iteration)
    public void setupIteration() {
        eventWindow = EventWindow.getGenesisEventWindow();
        eventsCreated = 0;
        nextPeerIndex = 1;
        pendingSelfEvent = null;

        // Use production defaults except maxCreationRate which is disabled for latency measurement.
        // All other rules (PlatformStatusRule, PlatformHealthRule, SyncLagRule, QuiescenceRule) remain active.
        final Configuration configuration = new TestConfigBuilder()
                .withConfigDataType(EventCreationConfig.class)
                .withValue(EventCreationConfig_.MAX_CREATION_RATE, 0)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();
        final Metrics metrics = platformContext.getMetrics();
        final Time time = platformContext.getTime();

        allManagers = new ArrayList<>(numNodes);
        for (final RosterEntry entry : roster.rosterEntries()) {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            final SecureRandom nodeRandom = new SecureRandom();
            nodeRandom.setSeed(nodeId.id());
            final KeyPair keyPair = SigningFactory.generateKeyPair(signingType.getSigningSchema(), nodeRandom);
            final BytesSigner signer = SigningFactory.createSigner(signingType, keyPair);
            final EventCreator eventCreator =
                    new TipsetEventCreator(configuration, metrics, time, nodeRandom, signer, roster, nodeId, List::of);

            final DefaultEventCreationManager manager = new DefaultEventCreationManager(
                    configuration, metrics, time, () -> false, eventCreator, roster, nodeId);

            // Set platform status to ACTIVE, as in production steady state
            manager.updatePlatformStatus(PlatformStatus.ACTIVE);
            manager.setEventWindow(eventWindow);

            allManagers.add(manager);
        }
        selfManager = allManagers.getFirst();
        orphanBuffer = new DefaultOrphanBuffer(metrics, new NoOpIntakeEventCounter());

        // Bootstrap: each node creates a genesis event and distributes it to all others
        for (final DefaultEventCreationManager manager : allManagers) {
            final PlatformEvent genesis = manager.maybeCreateEvent();
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
            final DefaultEventCreationManager peer = allManagers.get(nextPeerIndex);
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
            for (final DefaultEventCreationManager manager : allManagers) {
                manager.setEventWindow(eventWindow);
            }
        }
    }

    /**
     * Measures the latency of a single {@link DefaultEventCreationManager#maybeCreateEvent()} call.
     * <p>
     * This exercises the complete event creator module code path: rule evaluation
     * ({@code AggregateEventCreationRules}), phase tracking ({@code PhaseTimer}), future event buffering
     * ({@code FutureEventBuffer}), tipset parent selection, event assembly, hashing, and signing.
     *
     * @return the created event (returned to prevent dead-code elimination)
     */
    /*
    Results on a M3 Max MacBook Pro:

    Benchmark                                               (numNodes)  (seed)   (signingType)    Mode     Cnt       Score    Error  Units
    EventCreatorModuleLatencyBenchmark.createEvent                   4       0          RSA_BC  sample   12378    2019.293 ±  3.772  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50             4       0          RSA_BC  sample            1996.800           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99             4       0          RSA_BC  sample            2233.180           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999            4       0          RSA_BC  sample            3867.054           us/op
    EventCreatorModuleLatencyBenchmark.createEvent                   4       0         RSA_JDK  sample   12877    1938.601 ±  2.891  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50             4       0         RSA_JDK  sample            1921.024           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99             4       0         RSA_JDK  sample            2146.304           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999            4       0         RSA_JDK  sample            3760.628           us/op
    EventCreatorModuleLatencyBenchmark.createEvent                   4       0  ED25519_SODIUM  sample  778071      14.055 ±  0.834  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50             4       0  ED25519_SODIUM  sample              13.040           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99             4       0  ED25519_SODIUM  sample              17.280           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999            4       0  ED25519_SODIUM  sample              32.896           us/op
    EventCreatorModuleLatencyBenchmark.createEvent                   4       0     ED25519_SUN  sample   66379     374.103 ±  0.998  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50             4       0     ED25519_SUN  sample             369.664           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99             4       0     ED25519_SUN  sample             417.280           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999            4       0     ED25519_SUN  sample             456.120           us/op
    EventCreatorModuleLatencyBenchmark.createEvent                  40       0          RSA_BC  sample   12107    2025.422 ± 15.270  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50            40       0          RSA_BC  sample            1994.752           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99            40       0          RSA_BC  sample            2240.512           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999           40       0          RSA_BC  sample            5191.074           us/op
    EventCreatorModuleLatencyBenchmark.createEvent                  40       0         RSA_JDK  sample   12604    1938.701 ±  3.642  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50            40       0         RSA_JDK  sample            1923.072           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99            40       0         RSA_JDK  sample            2134.016           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999           40       0         RSA_JDK  sample            5028.168           us/op
    EventCreatorModuleLatencyBenchmark.createEvent                  40       0  ED25519_SODIUM  sample  466444      15.185 ±  1.021  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50            40       0  ED25519_SODIUM  sample              14.368           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99            40       0  ED25519_SODIUM  sample              19.360           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999           40       0  ED25519_SODIUM  sample              34.496           us/op
    EventCreatorModuleLatencyBenchmark.createEvent                  40       0     ED25519_SUN  sample   57766     391.637 ±  2.879  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50            40       0     ED25519_SUN  sample             371.712           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99            40       0     ED25519_SUN  sample             547.840           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999           40       0     ED25519_SUN  sample            4366.336           us/op
    */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public PlatformEvent createEvent() {
        final PlatformEvent event = selfManager.maybeCreateEvent();
        if (event == null) {
            throw new IllegalStateException("Self manager should always be able to create an event");
        }
        pendingSelfEvent = event;
        return event;
    }

    /**
     * Processes an event through the orphan buffer (to assign nGen) and distributes it to all managers.
     */
    private void processAndDistribute(final PlatformEvent event) {
        orphanBuffer.handleEvent(event);
        for (final DefaultEventCreationManager manager : allManagers) {
            manager.registerEvent(event);
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
