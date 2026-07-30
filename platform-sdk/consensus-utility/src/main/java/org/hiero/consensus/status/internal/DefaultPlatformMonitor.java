// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.internal;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.hiero.consensus.freeze.FreezePeriodChecker;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.actions.CatastrophicFailureAction;
import org.hiero.consensus.status.actions.FreezePeriodEnteredAction;
import org.hiero.consensus.status.actions.PlatformStatusAction;
import org.hiero.consensus.status.actions.SelfEventReachedConsensusAction;
import org.hiero.consensus.status.actions.StateWrittenToDiskAction;
import org.hiero.consensus.status.actions.TimeElapsedAction;
import org.hiero.consensus.uptime.UptimeTracker;

/**
 * The default implementation of the {@link PlatformMonitor}.
 */
public class DefaultPlatformMonitor implements PlatformMonitor {
    /** The types of ISSs that should trigger a catastrophic failure */
    private static final Set<IssType> CATASTROPHIC_ISS_TYPES = Set.of(IssType.SELF_ISS, IssType.CATASTROPHIC_ISS);

    /** Time source for the platform monitor */
    private final Time time;
    /** The state machine that manages the platform status */
    private final StatusStateMachine statusStateMachine;
    /** Tracks the node's uptime based on consensus events */
    private final UptimeTracker uptimeTracker;
    /** Tracks the last QuiescenceCommand submitted to the node */
    private QuiescenceCommand lastQuiescenceCommand;
    /** Tracks the moment a QuiescenceCommand was submitted to the node */
    private Instant lastQuiescenceCommandTime;
    /** Checks if a time is in the freeze period */
    @NonNull
    private FreezePeriodChecker freezePeriodChecker;

    /**
     * Create a new platform monitor.
     *
     * @param configuration the configuration
     * @param metrics       the metrics
     * @param time          the time source
     * @param selfId        the ID of this node
     */
    public DefaultPlatformMonitor(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final FreezePeriodChecker freezePeriodChecker) {
        this.time = requireNonNull(time);
        this.freezePeriodChecker = requireNonNull(freezePeriodChecker);
        statusStateMachine = new StatusStateMachine(configuration, metrics, time);
        uptimeTracker = new UptimeTracker(configuration, metrics, time, selfId);
        lastQuiescenceCommand = QuiescenceCommand.DONT_QUIESCE;
        lastQuiescenceCommandTime = time.now();
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public PlatformStatus submitStatusAction(@NonNull final PlatformStatusAction action) {
        return statusStateMachine.submitStatusAction(action);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PlatformStatus heartbeat(@NonNull final Instant time) {
        return statusStateMachine.submitStatusAction(new TimeElapsedAction(
                time,
                new TimeElapsedAction.QuiescingStatus(
                        lastQuiescenceCommand == QuiescenceCommand.QUIESCE, lastQuiescenceCommandTime)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PlatformStatus consensusRound(@NonNull final ConsensusRound round) {
        if (freezePeriodChecker.isInFreezePeriod(round.getConsensusTimestamp())) {
            return statusStateMachine.submitStatusAction(new FreezePeriodEnteredAction(round.getRoundNum()));
        }

        final boolean selfEventReachedConsensus = uptimeTracker.trackRound(round);
        if (!selfEventReachedConsensus) {
            return null;
        }
        // the action receives the wall clock time, NOT the consensus timestamp
        return statusStateMachine.submitStatusAction(new SelfEventReachedConsensusAction(time.now()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void quiescenceCommand(@NonNull final QuiescenceCommand command) {
        if (lastQuiescenceCommand != requireNonNull(command)) {
            lastQuiescenceCommand = command;
            lastQuiescenceCommandTime = time.now();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PlatformStatus stateWrittenToDisk(@NonNull final StateSavingResult result) {
        return statusStateMachine.submitStatusAction(
                new StateWrittenToDiskAction(result.round(), result.freezeState()));
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public PlatformStatus issNotification(@NonNull final IssNotification notification) {
        if (CATASTROPHIC_ISS_TYPES.contains(notification.getIssType())) {
            return statusStateMachine.submitStatusAction(new CatastrophicFailureAction());
        }
        // don't change status for other types of ISSs
        return null;
    }
}
