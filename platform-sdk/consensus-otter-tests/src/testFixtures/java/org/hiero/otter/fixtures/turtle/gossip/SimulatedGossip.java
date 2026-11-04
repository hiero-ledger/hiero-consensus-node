// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.gossip;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.gossip.impl.gossip.Gossip;
import org.hiero.consensus.gossip.impl.gossip.sync.protocol.SyncStatusChecker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.monitoring.FallenBehindStatus;
import org.hiero.consensus.status.monitor.actions.FallenBehindAction;
import org.hiero.consensus.status.monitor.actions.PlatformStatusAction;
import org.hiero.consensus.wiring.framework.model.DeterministicWiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.wires.input.BindableInputWire;
import org.hiero.consensus.wiring.framework.wires.input.NoInput;
import org.hiero.consensus.wiring.framework.wires.output.StandardOutputWire;
import org.hiero.otter.fixtures.network.simulation.EventReceiver;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;

/**
 * Simulates the {@link Gossip} subsystem for a group of nodes running on a {@link SimulatedNetworkConnectivity}.
 */
public class SimulatedGossip implements Gossip, EventReceiver {

    private final SimulatedNetworkConnectivity networkConnectivity;
    private final NodeId selfId;
    private IntakeEventCounter intakeEventCounter;

    private StandardOutputWire<PlatformEvent> eventOutput;
    private StandardOutputWire<SyncProgress> syncProgressOutput;

    /** The most recent event window of this node, used as the local window of every {@link SyncProgress}. */
    private volatile EventWindow ownEventWindow = EventWindow.getGenesisEventWindow();

    /** The most recent event window received from each peer. */
    private final Map<NodeId, EventWindow> lastPeerWindows = new ConcurrentHashMap<>();

    /** The wiring model used for this node. Used to determine if the node is running or halted. */
    private DeterministicWiringModel deterministicWiringModel;

    /** The latest platform status of this node. Gossip is only received in statuses that permit syncing. */
    private volatile PlatformStatus platformStatus = PlatformStatus.STARTING_UP;

    /** Evaluates whether this node has fallen behind a peer, or vice versa. Null until provided. */
    private volatile FallenBehindMonitor fallenBehindMonitor;

    /** Submits actions to the status monitor of this node. Null until provided. */
    private volatile Consumer<PlatformStatusAction> statusActionSubmitter;

    /** The latest fallen-behind status of each peer. A missing entry means the peer has not been evaluated yet. */
    private final Map<NodeId, FallenBehindStatus> peerStatuses = new ConcurrentHashMap<>();

    /** Whether a {@link FallenBehindAction} has been submitted since the last (re)start. */
    private boolean fallenBehindSubmitted;

    /**
     * Constructor.
     *
     * @param networkConnectivity the network connections on which this gossip system will run
     * @param selfId the ID of the node running this gossip system
     */
    public SimulatedGossip(
            @NonNull final SimulatedNetworkConnectivity networkConnectivity, @NonNull final NodeId selfId) {
        this.networkConnectivity = requireNonNull(networkConnectivity);
        this.selfId = requireNonNull(selfId);
    }

    /**
     * Add an intake event counter that gets incremented for all events that enter the intake pipeline.
     *
     * @param intakeEventCounter the intake event counter
     */
    public void provideIntakeEventCounter(@NonNull final IntakeEventCounter intakeEventCounter) {
        this.intakeEventCounter = requireNonNull(intakeEventCounter);
    }

    /**
     * Provide the monitor that evaluates whether this node has fallen behind. Evaluation starts from a clean slate.
     *
     * @param fallenBehindMonitor the fallen behind monitor
     */
    public void provideFallenBehindMonitor(@NonNull final FallenBehindMonitor fallenBehindMonitor) {
        this.fallenBehindMonitor = requireNonNull(fallenBehindMonitor);
        resetFallenBehindState();
    }

    /**
     * Provide the means to submit actions to the status monitor of this node.
     *
     * @param statusActionSubmitter submits a status action
     */
    public void provideStatusActionSubmitter(@NonNull final Consumer<PlatformStatusAction> statusActionSubmitter) {
        this.statusActionSubmitter = requireNonNull(statusActionSubmitter);
    }

    private synchronized void resetFallenBehindState() {
        lastPeerWindows.clear();
        peerStatuses.clear();
        fallenBehindSubmitted = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bind(
            @NonNull final WiringModel model,
            @NonNull final BindableInputWire<PlatformEvent, Void> eventInput,
            @NonNull final BindableInputWire<EventWindow, Void> eventWindowInput,
            @NonNull final StandardOutputWire<PlatformEvent> eventOutput,
            @NonNull final BindableInputWire<NoInput, Void> startInput,
            @NonNull final BindableInputWire<NoInput, Void> stopInput,
            @NonNull final BindableInputWire<NoInput, Void> clearInput,
            @NonNull final BindableInputWire<NoInput, Void> pauseInput,
            @NonNull final BindableInputWire<NoInput, Void> resumeInput,
            @NonNull final BindableInputWire<Duration, Void> systemHealthInput,
            @NonNull final BindableInputWire<PlatformStatus, Void> platformStatusInput,
            @NonNull final StandardOutputWire<SyncProgress> syncProgressOutput) {

        this.eventOutput = requireNonNull(eventOutput);
        this.syncProgressOutput = requireNonNull(syncProgressOutput);
        this.deterministicWiringModel = (DeterministicWiringModel) requireNonNull(model);
        eventInput.bindConsumer(event -> {
            // Self-created events have no sender until now; the network identifies the source by this field
            event.setSenderId(selfId);
            networkConnectivity.submitEvent(event);
        });
        eventWindowInput.bindConsumer(eventWindow -> {
            ownEventWindow = eventWindow;
            networkConnectivity.updateEventWindow(selfId, eventWindow);
            // Production reports on every sync with both windows current. Without this, the lag would stay at its
            // last value while this node catches up against peers whose windows no longer change.
            lastPeerWindows.forEach((peerId, peerWindow) -> {
                syncProgressOutput.forward(new SyncProgress(peerId, eventWindow, peerWindow));
                checkPeer(eventWindow, peerId, peerWindow);
            });
            submitFallenBehindIfNeeded();
        });

        startInput.bindConsumer(ignored -> {});
        stopInput.bindConsumer(ignored -> {});
        clearInput.bindConsumer(ignored -> {});
        pauseInput.bindConsumer(ignored -> {});
        resumeInput.bindConsumer(ignored -> {});
        systemHealthInput.bindConsumer(ignored -> {});
        // A restarted node starts from scratch, like in production
        platformStatus = PlatformStatus.STARTING_UP;
        platformStatusInput.bindConsumer(status -> platformStatus = status);
    }

    /**
     * In production a node neither sends nor receives gossip in statuses that don't permit syncing.
     */
    private boolean canReceive() {
        return deterministicWiringModel.isRunning() && SyncStatusChecker.doesStatusPermitSync(platformStatus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean receiveEvent(@NonNull final PlatformEvent event) {
        if (canReceive()
                && peerStatuses.getOrDefault(event.getSenderId(), FallenBehindStatus.NONE_FALLEN_BEHIND)
                        == FallenBehindStatus.NONE_FALLEN_BEHIND) {
            forwardEvent(event);
            return true;
        }
        return false;
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
     *
     * <p>Remembers the peer's window and reports the sync progress against that peer to the event creator. The peer
     * is also checked for being fallen behind (or us being behind it) synchronously, so that the verdict applies to
     * events of the same tick that are delivered right after this window.
     */
    @Override
    public boolean receiveEventWindow(@NonNull final NodeId sender, @NonNull final EventWindow eventWindow) {
        if (!canReceive()) {
            return false;
        }
        lastPeerWindows.put(sender, eventWindow);
        syncProgressOutput.forward(new SyncProgress(sender, ownEventWindow, eventWindow));
        checkPeer(ownEventWindow, sender, eventWindow);
        submitFallenBehindIfNeeded();
        return true;
    }

    private void checkPeer(
            @NonNull final EventWindow own, @NonNull final NodeId peer, @NonNull final EventWindow peerWindow) {
        final FallenBehindMonitor monitor = fallenBehindMonitor;
        if (monitor != null) {
            peerStatuses.put(peer, monitor.check(own, peerWindow, peer));
        }
    }

    private synchronized void submitFallenBehindIfNeeded() {
        final FallenBehindMonitor monitor = fallenBehindMonitor;
        final Consumer<PlatformStatusAction> submitter = statusActionSubmitter;
        if (!fallenBehindSubmitted && monitor != null && submitter != null && monitor.hasFallenBehind()) {
            fallenBehindSubmitted = true;
            submitter.accept(new FallenBehindAction());
        }
    }

    private void forwardEvent(@NonNull final PlatformEvent event) {
        if (intakeEventCounter != null) {
            intakeEventCounter.eventEnteredIntakePipeline(event.getSenderId());
        }
        eventOutput.forward(event);
    }

    /**
     * Resets this node's gossip point so that it will receive all necessary events after a restart.
     */
    public void onRestart() {
        networkConnectivity.resetCursor(selfId);
        // The restarted event creator has no lag recorded; stale peer windows must not be replayed against it.
        lastPeerWindows.clear();
    }
}
