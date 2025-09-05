package com.swirlds.platform.system;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.system.status.DefaultStatusStateMachine;
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
import java.util.Set;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;

public class DefaultPlatformMonitor implements PlatformMonitor {
    private final Time time;
    private final DefaultStatusStateMachine statusStateMachine;
    private final UptimeTracker uptimeTracker;

    public DefaultPlatformMonitor(@NonNull final PlatformContext platformContext, @NonNull final NodeId selfId) {
        time = platformContext.getTime();
        statusStateMachine = new DefaultStatusStateMachine(platformContext);
        uptimeTracker = new UptimeTracker(
                platformContext,
                selfId);
    }

    @Nullable
    @Override
    public PlatformStatus submitStatusAction(@NonNull final PlatformStatusAction action) {
        return statusStateMachine.submitStatusAction(action);
    }

    @Override
    public PlatformStatus heartbeat(@NonNull final Instant time) {
        return statusStateMachine.submitStatusAction(new TimeElapsedAction(time));
    }

    @Override
    public PlatformStatus consensusRound(@NonNull final ConsensusRound round) {
        final boolean selfEventReachedConsensus = uptimeTracker.trackRound(round);
        if (!selfEventReachedConsensus) {
            return null;
        }
        // the action receives the wall clock time, NOT the consensus timestamp
        return statusStateMachine.submitStatusAction(new SelfEventReachedConsensusAction(time.now()));
    }

    @Override
    public PlatformStatus stateWrittenToDisk(@NonNull final StateSavingResult result) {
        return statusStateMachine.submitStatusAction(
                new StateWrittenToDiskAction(result.round(), result.freezeState())
        );
    }

    @Nullable
    @Override
    public PlatformStatus issNotification(final List<IssNotification> notifications) {
        final Set<IssType> issTypes = Set.of(IssType.SELF_ISS, IssType.CATASTROPHIC_ISS);
        if (notifications.stream().map(IssNotification::getIssType).anyMatch(issTypes::contains)) {
            return statusStateMachine.submitStatusAction(new CatastrophicFailureAction());
        }
        // don't change status for other types of ISSs
        return null;
    }
}
