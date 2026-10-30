// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.falcon.RecordingEventCreationManager.copyEvent;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.RUNNING;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.consensus.benchmark.tools.event.EventShape;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.gossip.impl.gossip.sync.protocol.SyncStatusChecker;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.status.monitor.actions.DoneReplayingEventsAction;
import org.hiero.consensus.status.monitor.actions.StartedReplayingEventsAction;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.falcon.recording.Step;
import org.hiero.otter.fixtures.falcon.recording.Step.Received;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.NetworkConfiguration;
import org.hiero.otter.fixtures.internal.result.ConsensusRoundPool;
import org.hiero.otter.fixtures.internal.result.NodeResultsCollector;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTimeManager;
import org.hiero.otter.fixtures.network.simulation.EventReceiver;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeEventStreamResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.hiero.otter.fixtures.util.SecureRandomBuilder;

/**
 * An implementation of {@link Node} that is based on the Falcon framework.
 */
public class FalconNode extends AbstractNode implements Node, TimeTickReceiver, EventReceiver {

    private final Random random;
    private final SimulatorTimeManager timeManager;
    private final SimulatedNetworkConnectivity networkConnectivity;
    private final NodeConfiguration nodeConfiguration;
    private final NodeResultsCollector resultsCollector;

    /** The shape the transactions of created events are drawn from, or {@code null} for events without any. */
    @Nullable
    private final EventShape transactionShape;

    /** Whether this node signs its events with its own key. */
    private final boolean signEvents;

    @Nullable
    private FalconWiring wiring;

    @NonNull
    private EventWindow latestEventWindow = EventWindow.getGenesisEventWindow();

    /** Receives every step of this node, or {@code null} if nothing is recorded. */
    @Nullable
    private Consumer<Step> stepRecorder;

    /** The seed of the event creator's random, drawn when the node starts. */
    private long creatorSeed;

    /** The time the node built its event creator, or {@code null} before the node starts. */
    @Nullable
    private Instant creatorStartTime;

    /**
     * Constructor for {@code FalconNode}.
     *
     * @param random the random number generator
     * @param timeManager the time manager
     * @param selfId the ID of this node
     * @param keysAndCerts the keys and certificates of this node
     * @param networkConnectivity the simulated network connectivity
     * @param networkConfiguration the network configuration
     * @param consensusRoundPool the consensus round pool that collects and deduplicates consensus rounds
     * @param transactionShape the shape the transactions of created events are drawn from, or {@code null} for events
     * without any
     * @param signEvents whether this node signs its events with its own key
     */
    public FalconNode(
            @NonNull final Random random,
            @NonNull final SimulatorTimeManager timeManager,
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final SimulatedNetworkConnectivity networkConnectivity,
            @NonNull final NetworkConfiguration networkConfiguration,
            @NonNull final ConsensusRoundPool consensusRoundPool,
            @Nullable final EventShape transactionShape,
            final boolean signEvents) {
        super(selfId, keysAndCerts, networkConfiguration);
        this.random = requireNonNull(random);
        this.timeManager = requireNonNull(timeManager);
        this.networkConnectivity = requireNonNull(networkConnectivity);
        this.transactionShape = transactionShape;
        this.signEvents = signEvents;

        this.nodeConfiguration =
                new FalconNodeConfiguration(() -> lifeCycle, networkConfiguration.overrideProperties());
        this.resultsCollector = new NodeResultsCollector(selfId, consensusRoundPool);
    }

    /**
     * Passes every step of this node to a recorder, from the start of the node on: every event it receives, and every
     * call its event creation manager gets. Nothing is recorded without it.
     *
     * @param stepRecorder receives the steps
     * @throws IllegalStateException if the node has been started already
     */
    public void stepRecorder(@NonNull final Consumer<Step> stepRecorder) {
        throwIfInLifecycle(RUNNING, "Steps must be recorded from the start of the node.");
        throwIfInLifecycle(DESTROYED, "Node has already been destroyed.");
        this.stepRecorder = requireNonNull(stepRecorder);
    }

    /**
     * Returns the seed of the random that the event creator of this node got when the node started. The event creator
     * gets {@code new SecureRandomBuilder(creatorSeed()).get()}.
     *
     * @return the seed
     * @throws IllegalStateException if the node has not been started yet
     */
    public long creatorSeed() {
        throwIfNotStarted();
        return creatorSeed;
    }

    /**
     * Returns the simulated time at which this node built its event creator.
     *
     * @return the time
     * @throws IllegalStateException if the node has not been started yet
     */
    @NonNull
    public Instant creatorStartTime() {
        throwIfNotStarted();
        assert creatorStartTime != null;
        return creatorStartTime;
    }

    private void throwIfNotStarted() {
        if (creatorStartTime == null) {
            throw new IllegalStateException("The node has not been started yet.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeId getNodeId() {
        return selfId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean receiveEvent(@NonNull final PlatformEvent event) {
        if (receiveAllowed()) {
            assert wiring != null;
            if (stepRecorder != null) {
                // Before the event goes onto the wire: on direct schedulers, the put releases it right away
                final NodeId sender = requireNonNull(event.getSenderId());
                stepRecorder.accept(new Received(timeManager.now(), sender, copyEvent(event, EventOrigin.GOSSIP)));
            }
            wiring.receivedGossipEventsInputWire().put(event);
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nothing consumes a peer's event window yet. The network delivers them so that the machinery is in place, but
     * this node has nowhere to route them until fallen-behind detection is wired up.
     */
    @Override
    public boolean receiveEventWindow(@NonNull final NodeId sender, @NonNull final EventWindow eventWindow) {
        return receiveAllowed();
    }

    private boolean receiveAllowed() {
        final PlatformStatus status = platformStatus;
        return wiring != null && status != null && SyncStatusChecker.doesStatusPermitSync(status);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected TimeManager timeManager() {
        return timeManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected Random random() {
        return random;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStart(@NonNull final Duration timeout) {
        throwIfInLifecycle(RUNNING, "Node has already been started.");
        throwIfInLifecycle(DESTROYED, "Node has already been destroyed.");

        final Configuration currentConfiguration = nodeConfiguration.current();
        final Time time = timeManager.time();
        creatorSeed = random.nextLong();
        creatorStartTime = time.now();
        final SecureRandom secureRandom = new SecureRandomBuilder(creatorSeed).get();
        final BytesSigner signer = signEvents ? new PlatformSigner(keysAndCerts) : _ -> Bytes.EMPTY;
        final EventTransactionSupplier transactionSupplier = transactionShape == null
                ? List::of
                : new ShapedTransactionSupplier(transactionShape, random.nextLong(), time);

        wiring = new FalconWiring(
                currentConfiguration, time, selfId, roster(), secureRandom, signer, transactionSupplier, stepRecorder);
        final Consumer<PlatformEvent> eventSubmitter = event -> {
            event.setSenderId(selfId);
            networkConnectivity.submitEvent(event);
        };
        wiring.sentGossipEventsOutputWire().solderTo("EventSubmitter_" + selfId, "event", eventSubmitter);
        final Consumer<EventWindow> eventWindowSubmitter = eventWindow -> {
            latestEventWindow = eventWindow;
            networkConnectivity.updateEventWindow(selfId, eventWindow);
        };
        wiring.eventWindowOutputWire().solderTo("EventWindowSubmitter_" + selfId, "event window", eventWindowSubmitter);
        wiring.consensusRoundOutputWire()
                .solderTo("ResultsCollector", "consensus round", resultsCollector::addConsensusRound);
        wiring.platformStatusOutputWire()
                .solderTo("PlatformStatusCollector", "platform status", this::handlePlatformStatusChange);

        platformStatus = PlatformStatus.STARTING_UP;

        wiring.start();

        // Without PCES there is nothing to replay, but the status monitor only leaves STARTING_UP through
        // REPLAYING_EVENTS. Submit the actions PcesCoordinator would send until in-memory PCES replaces this (#26824).
        wiring.platformStatusActionInputWire().put(new StartedReplayingEventsAction());
        wiring.platformStatusActionInputWire().put(new DoneReplayingEventsAction(time.now()));

        lifeCycle = RUNNING;
    }

    private void handlePlatformStatusChange(@NonNull final PlatformStatus platformStatus) {
        this.platformStatus = requireNonNull(platformStatus);
        resultsCollector.addPlatformStatus(platformStatus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doKillImmediately(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Killing a node is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStartSyntheticBottleneck(@NonNull final Duration delayPerRound, @NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Synthetic bottleneck is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStopSyntheticBottleneck(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Synthetic bottleneck is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doSendQuiescenceCommand(@NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Quiescence command is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransactions(@NonNull final List<OtterTransaction> transactions) {
        throw new UnsupportedOperationException("Transactions are not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doTriggerSelfIss(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Self ISS is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startFromSavedState(@NonNull final Path savedStateDirectory) {
        throw new UnsupportedOperationException("Starting from a saved state is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void version(@NonNull final SemanticVersion version) {
        throw new UnsupportedOperationException("Versions are not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bumpConfigVersion() {
        throw new UnsupportedOperationException("Versions are not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration configuration() {
        return nodeConfiguration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeConsensusResult newConsensusResult() {
        return resultsCollector.newConsensusResult();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeLogResult newLogResult() {
        throw new UnsupportedOperationException("Log results are not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePlatformStatusResult newPlatformStatusResult() {
        return resultsCollector.newStatusProgression();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePcesResult newPcesResult() {
        throw new UnsupportedOperationException("PCES is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeReconnectResult newReconnectResult() {
        throw new UnsupportedOperationException("Reconnect is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeEventStreamResult newEventStreamResult() {
        throw new UnsupportedOperationException("Event stream is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public EventWindow latestEventWindow() {
        return latestEventWindow;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        if (wiring != null) {
            wiring.tick(now);
        }
    }
}
