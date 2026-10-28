// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.RUNNING;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.consensus.benchmark.tools.event.EventShape;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.creator.config.EventCreationConfig;
import org.hiero.consensus.event.creator.impl.DefaultEventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;
import org.hiero.otter.fixtures.MiniNode;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractMiniNode;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.NetworkConfiguration;
import org.hiero.otter.fixtures.network.simulation.EventReceiver;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;
import org.hiero.otter.fixtures.util.SecureRandomBuilder;

@SuppressWarnings("ForLoopReplaceableByForEach")
public class FalconMiniNode extends AbstractMiniNode implements MiniNode, TimeTickReceiver, EventReceiver {

    /** The random number generator for this node. */
    private final Random random;

    /** The time source for this node. */
    private final Time time;

    /** The network connectivity for this node. */
    private final SimulatedNetworkConnectivity networkConnectivity;

    /** The configuration for this node. */
    private final NodeConfiguration nodeConfiguration;

    /** The metrics for this node. */
    private final Metrics metrics = new NoOpMetrics();

    /** The intake for this node, which takes the place of an event deduplicator and an orphan buffer. */
    private final FalconMiniIntake intake = new FalconMiniIntake();

    /** The event creation manager for this node. */
    @Nullable
    private EventCreationManager eventCreationManager;

    /** The current event window for this node. */
    private EventWindow currentEventWindow = EventWindow.getGenesisEventWindow();

    /** The configured {@code event.creation.period} in nanoseconds, read once when the node starts. */
    private long creationPeriodNanos;

    /** The time of the previous event creation attempt in nanoseconds since the epoch. */
    private long previousCreationAttemptNanos;

    /** The shape the transactions of created events are drawn from, or {@code null} for empty events. */
    @Nullable
    private final EventShape transactionShape;

    /** Whether this node signs its events with its own key. */
    private final boolean signEvents;

    /**
     * Constructor for {@code FalconMiniNode}.
     *
     * @param random the random number generator of the network
     * @param time the time source
     * @param selfId the ID of this node
     * @param keysAndCerts the keys and certificates of this node
     * @param networkConnectivity the simulated network connectivity
     * @param networkConfiguration the network configuration
     * @param transactionShape the shape the transactions of created events are drawn from, or {@code null} for empty events
     * @param signEvents whether this node signs its events with its own key
     */
    public FalconMiniNode(
            @NonNull final Random random,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final SimulatedNetworkConnectivity networkConnectivity,
            @NonNull final NetworkConfiguration networkConfiguration,
            @Nullable final EventShape transactionShape,
            final boolean signEvents) {
        super(selfId, keysAndCerts, networkConfiguration);

        this.random = requireNonNull(random);
        this.time = requireNonNull(time);
        this.networkConnectivity = requireNonNull(networkConnectivity);
        this.nodeConfiguration =
                new FalconNodeConfiguration(() -> lifeCycle, networkConfiguration.overrideProperties());
        this.transactionShape = transactionShape;
        this.signEvents = signEvents;
    }

    @Override
    public NodeId getNodeId() {
        return selfId;
    }

    @Override
    public boolean receiveEvent(@NonNull final PlatformEvent event) {
        if (lifeCycle != RUNNING) {
            return false;
        }

        registerAndSubmit(intake.handleEvent(event));
        return true;
    }

    @Override
    public boolean receiveEventWindow(@NonNull final NodeId sender, @NonNull final EventWindow eventWindow) {
        if (lifeCycle != RUNNING) {
            return false;
        }
        assert eventCreationManager != null;

        if (eventWindow.latestConsensusRound() > currentEventWindow.latestConsensusRound()) {
            currentEventWindow = eventWindow;
            final List<PlatformEvent> orderedEvents = intake.setEventWindow(eventWindow);
            eventCreationManager.setEventWindow(eventWindow);
            networkConnectivity.updateEventWindow(selfId, eventWindow);
            registerAndSubmit(orderedEvents);
        }
        return true;
    }

    /**
     * Registers events released by the intake with the event creation manager and submits them to the network,
     * in the order they were released.
     *
     * @param orderedEvents the released events
     */
    private void registerAndSubmit(@NonNull final List<PlatformEvent> orderedEvents) {
        assert eventCreationManager != null;
        for (int i = 0, n = orderedEvents.size(); i < n; i++) {
            final PlatformEvent orderedEvent = orderedEvents.get(i);
            eventCreationManager.registerEvent(orderedEvent);
            orderedEvent.setSenderId(selfId);
            networkConnectivity.submitEvent(orderedEvent);
        }
    }

    @Override
    protected void doStart(@NonNull final Duration timeout) {
        throwIfInLifecycle(RUNNING, "Node has already been started.");
        throwIfInLifecycle(DESTROYED, "Node has already been destroyed.");

        final Configuration configuration = nodeConfiguration.current();
        final SignatureTransactionCheck signatureTransactionCheck = () -> false;
        final SecureRandom secureRandom = new SecureRandomBuilder(random.nextLong()).get();
        final BytesSigner signer = signEvents ? new PlatformSigner(keysAndCerts) : _ -> Bytes.EMPTY;
        final RosterWrapper roster = RosterWrapper.of(roster());
        final EventTransactionSupplier transactionSupplier = transactionShape == null
                ? List::of
                : new ShapedTransactionSupplier(transactionShape, random.nextLong(), time);

        final EventCreator eventCreator = new TipsetEventCreator(
                configuration, metrics, time, secureRandom, signer, roster, selfId, transactionSupplier);
        eventCreationManager = new DefaultEventCreationManager(
                configuration, metrics, time, signatureTransactionCheck, eventCreator, roster, selfId);
        eventCreationManager.updatePlatformStatus(PlatformStatus.ACTIVE);

        creationPeriodNanos =
                configuration.getConfigData(EventCreationConfig.class).period().toNanos();
        final Instant now = time.now();
        previousCreationAttemptNanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();

        lifeCycle = RUNNING;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doKillImmediately(@NonNull final Duration timeout) {
        lifeCycle = DESTROYED;
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
    public EventWindow latestEventWindow() {
        return currentEventWindow;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Attempts to create an event once at least {@code event.creation.period} has passed since the previous attempt,
     * as the heartbeat of a regular node does. A created event takes the same path as the wiring gives it: it enters
     * the intake like an event received from a peer.
     */
    @Override
    public void tick(@NonNull final Instant now) {
        if (lifeCycle != RUNNING) {
            return;
        }
        assert eventCreationManager != null;

        final long nowNanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();
        if (nowNanos - previousCreationAttemptNanos < creationPeriodNanos) {
            return;
        }
        previousCreationAttemptNanos = nowNanos;

        final PlatformEvent newEvent = eventCreationManager.maybeCreateEvent();
        if (newEvent != null) {
            receiveEvent(newEvent);
        }
    }
}
