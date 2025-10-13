// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.system.status.StatusStateMachine;
import com.swirlds.platform.system.status.actions.CatastrophicFailureAction;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import com.swirlds.platform.system.status.actions.SelfEventReachedConsensusAction;
import com.swirlds.platform.system.status.actions.StateWrittenToDiskAction;
import com.swirlds.platform.system.status.actions.TimeElapsedAction;
import com.swirlds.platform.uptime.UptimeTracker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;

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

    /**
     * Create a new platform monitor.
     *
     * @param platformContext the platform context
     * @param selfId          the ID of this node
     */
    public DefaultPlatformMonitor(@NonNull final PlatformContext platformContext, @NonNull final NodeId selfId) {
        time = platformContext.getTime();
        statusStateMachine = new StatusStateMachine(platformContext);
        uptimeTracker = new UptimeTracker(platformContext, selfId);
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
        if (lastQuiescenceCommand != Objects.requireNonNull(command)) {
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
    public PlatformStatus issNotification(@NonNull final List<IssNotification> notifications) {
        if (notifications.stream().map(IssNotification::getIssType).anyMatch(CATASTROPHIC_ISS_TYPES::contains)) {
            return statusStateMachine.submitStatusAction(new CatastrophicFailureAction());
        }
        // don't change status for other types of ISSs
        return null;
    }
}
