// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.jmh;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.crypto.SigningSchema;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.event.creator.config.EventCreationConfig;
import org.hiero.consensus.event.creator.config.EventCreationConfig_;
import org.hiero.consensus.event.creator.impl.DefaultEventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
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
 * event creator module, replicating the exact setup of {@link
 * org.hiero.consensus.event.creator.impl.DefaultEventCreatorModule}.
 *
 * <p>The self node (node 0) is wired through a {@link ComponentWiring} with a {@code DIRECT}
 * scheduler, matching the production module's component architecture. A {@link PlatformSigner}
 * created from {@link KeysAndCerts} is used for signing, exactly as the module does. Peer nodes
 * use direct {@link DefaultEventCreationManager} instances since only the self node's latency
 * is measured.
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
    public SigningSchema signingSchema;

    // --- Self node: ComponentWiring (matching DefaultEventCreatorModule) ---

    /** Input wire that triggers {@link EventCreationManager#maybeCreateEvent()}. */
    private InputWire<Void> creationTriggerWire;

    /** Input wire for {@link EventCreationManager#registerEvent(PlatformEvent)}. */
    private InputWire<PlatformEvent> registerEventWire;

    /** Input wire for {@link EventCreationManager#setEventWindow(EventWindow)}. */
    private InputWire<EventWindow> eventWindowWire;

    /** Input wire for {@link EventCreationManager#updatePlatformStatus(PlatformStatus)}. */
    private InputWire<PlatformStatus> platformStatusWire;

    /** The last event captured from the output wire after triggering creation. */
    private PlatformEvent lastCreatedEvent;

    // --- Peer nodes: direct managers ---

    /** All peer event creation managers (nodes 1..numNodes-1), indexed 0-based. */
    private List<DefaultEventCreationManager> peerManagers;

    // --- Shared state ---

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
     * Sets up the self node with {@link ComponentWiring} (matching
     * {@link org.hiero.consensus.event.creator.impl.DefaultEventCreatorModule}) and peer nodes
     * with direct managers. Bootstraps the network with genesis events.
     * This runs once per measurement iteration, giving a clean slate for each set of samples.
     */
    @Setup(Level.Iteration)
    public void setupIteration() {
        eventWindow = EventWindow.getGenesisEventWindow();
        eventsCreated = 0;
        nextPeerIndex = 1;
        pendingSelfEvent = null;
        lastCreatedEvent = null;

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

        // --- Self node: ComponentWiring setup (replicates DefaultEventCreatorModule.initialize()) ---
        final NodeId selfNodeId = NodeId.of(roster.rosterEntries().getFirst().nodeId());

        final WiringModel wiringModel = WiringModelBuilder.create(metrics, time).build();
        final ComponentWiring<EventCreationManager, PlatformEvent> selfWiring =
                new ComponentWiring<>(wiringModel, EventCreationManager.class, TaskSchedulerConfiguration.parse("DIRECT"));

        // Declare all input wires (matching DefaultEventCreatorModule.initialize())
        creationTriggerWire = selfWiring.getInputWire(EventCreationManager::maybeCreateEvent, "heartbeat");
        registerEventWire = selfWiring.getInputWire(EventCreationManager::registerEvent);
        eventWindowWire = selfWiring.getInputWire(EventCreationManager::setEventWindow, "event window");
        platformStatusWire = selfWiring.getInputWire(EventCreationManager::updatePlatformStatus, "PlatformStatus");
        selfWiring.getInputWire(EventCreationManager::reportUnhealthyDuration, "health info");
        selfWiring.getInputWire(EventCreationManager::reportSyncProgress);
        selfWiring.getInputWire(EventCreationManager::clear);
        selfWiring.getInputWire(EventCreationManager::quiescenceCommand);

        // Capture created events from the output wire
        selfWiring.getOutputWire().solderForMonitoring(event -> lastCreatedEvent = event);

        // Create signer from KeysAndCerts (matching DefaultEventCreatorModule.initialize())
        final KeysAndCerts selfKeysAndCerts = generateKeysAndCerts(selfNodeId);
        final BytesSigner selfSigner = new PlatformSigner(selfKeysAndCerts);

        final SecureRandom selfRandom = new SecureRandom();
        selfRandom.setSeed(selfNodeId.id());
        final EventCreator selfEventCreator =
                new TipsetEventCreator(configuration, metrics, time, selfRandom, selfSigner, roster, selfNodeId, List::of);
        final DefaultEventCreationManager selfManager = new DefaultEventCreationManager(
                configuration, metrics, time, () -> false, selfEventCreator, roster, selfNodeId);
        selfWiring.bind(selfManager);

        // Set initial state via wires
        platformStatusWire.put(PlatformStatus.ACTIVE);
        eventWindowWire.put(eventWindow);

        // --- Peer nodes: direct managers ---
        peerManagers = new ArrayList<>(numNodes - 1);
        for (int i = 1; i < roster.rosterEntries().size(); i++) {
            final RosterEntry entry = roster.rosterEntries().get(i);
            final NodeId nodeId = NodeId.of(entry.nodeId());
            final KeysAndCerts keysAndCerts = generateKeysAndCerts(nodeId);
            final BytesSigner signer = new PlatformSigner(keysAndCerts);
            final SecureRandom nodeRandom = new SecureRandom();
            nodeRandom.setSeed(nodeId.id());
            final EventCreator eventCreator =
                    new TipsetEventCreator(configuration, metrics, time, nodeRandom, signer, roster, nodeId, List::of);
            final DefaultEventCreationManager manager = new DefaultEventCreationManager(
                    configuration, metrics, time, () -> false, eventCreator, roster, nodeId);
            manager.updatePlatformStatus(PlatformStatus.ACTIVE);
            manager.setEventWindow(eventWindow);
            peerManagers.add(manager);
        }

        orphanBuffer = new DefaultOrphanBuffer(metrics, new NoOpIntakeEventCounter());

        // Bootstrap: each node creates a genesis event and distributes it to all others
        // Self genesis via wire
        lastCreatedEvent = null;
        creationTriggerWire.put(null);
        if (lastCreatedEvent != null) {
            processAndDistribute(lastCreatedEvent);
        }
        // Peer genesis directly
        for (final DefaultEventCreationManager peer : peerManagers) {
            final PlatformEvent genesis = peer.maybeCreateEvent();
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
            final DefaultEventCreationManager peer = peerManagers.get(nextPeerIndex - 1);
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
            eventWindowWire.put(eventWindow);
            for (final DefaultEventCreationManager manager : peerManagers) {
                manager.setEventWindow(eventWindow);
            }
        }
    }

    /**
     * Measures the latency of a single event creation through the complete module's
     * {@link ComponentWiring} stack.
     * <p>
     * This exercises the full production code path: wire dispatch, rule evaluation
     * ({@code AggregateEventCreationRules}), phase tracking ({@code PhaseTimer}), future event buffering
     * ({@code FutureEventBuffer}), tipset parent selection, event assembly, hashing, and signing via
     * {@link PlatformSigner}.
     *
     * @return the created event (returned to prevent dead-code elimination)
     */
    /*
    Results on a M3 Max MacBook Pro:

    Benchmark                                               (numNodes)  (seed)  (signingSchema)    Mode     Cnt       Score    Error  Units
    EventCreatorModuleLatencyBenchmark.createEvent                   4       0              RSA  sample   12384    2015.741 ±  3.272  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50             4       0              RSA  sample            2000.896           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99             4       0              RSA  sample            2220.032           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999            4       0              RSA  sample            3426.775           us/op
    EventCreatorModuleLatencyBenchmark.createEvent                   4       0          ED25519  sample  769502      13.868 ±  0.420  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50             4       0          ED25519  sample              13.152           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99             4       0          ED25519  sample              18.400           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999            4       0          ED25519  sample              31.568           us/op
    EventCreatorModuleLatencyBenchmark.createEvent                  40       0              RSA  sample   12138    2015.194 ±  4.126  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50            40       0              RSA  sample            1998.848           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99            40       0              RSA  sample            2215.936           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999           40       0              RSA  sample            4950.909           us/op
    EventCreatorModuleLatencyBenchmark.createEvent                  40       0          ED25519  sample  461018      15.135 ±  0.997  us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.50            40       0          ED25519  sample              14.320           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.99            40       0          ED25519  sample              19.040           us/op
    EventCreatorModuleLatencyBenchmark.createEvent:p0.999           40       0          ED25519  sample              38.271           us/op
    */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public PlatformEvent createEvent() {
        lastCreatedEvent = null;
        creationTriggerWire.put(null);
        if (lastCreatedEvent == null) {
            throw new IllegalStateException("Self manager should always be able to create an event");
        }
        pendingSelfEvent = lastCreatedEvent;
        return lastCreatedEvent;
    }

    /**
     * Processes an event through the orphan buffer (to assign nGen) and distributes it to
     * the self node via its input wire and to all peer managers directly.
     */
    private void processAndDistribute(final PlatformEvent event) {
        orphanBuffer.handleEvent(event);
        // Self via input wire (ComponentWiring path)
        registerEventWire.put(event);
        // Peers directly
        for (final DefaultEventCreationManager manager : peerManagers) {
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

    /**
     * Generates {@link KeysAndCerts} for the given node using the configured {@link #signingSchema}.
     */
    private KeysAndCerts generateKeysAndCerts(final NodeId nodeId) {
        try {
            final SecureRandom sigRandom = new SecureRandom();
            sigRandom.setSeed(nodeId.id() * 2L);
            final SecureRandom agrRandom = new SecureRandom();
            agrRandom.setSeed(nodeId.id() * 2L + 1);
            return KeysAndCertsGenerator.generate(nodeId, signingSchema, sigRandom, agrRandom);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to generate keys for node " + nodeId, e);
        }
    }
}
