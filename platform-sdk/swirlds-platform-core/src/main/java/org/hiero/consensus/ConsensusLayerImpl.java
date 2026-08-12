// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateAccessor.GENESIS_ROUND;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.freeze.FreezePeriodChecker;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.main.model.reconnect.PeerProtocolFactory;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.status.StatusMonitorModule;
import org.hiero.consensus.status.actions.CatastrophicFailureAction;
import org.hiero.consensus.status.actions.FreezeCompleteAction;

public class ConsensusLayerImpl implements ConsensusLayer {

    @NonNull
    private final Configuration configuration;

    @Nullable
    private final ConsensusSnapshot consensusSnapshot;

    @NonNull
    private final EventIntakeModule eventIntakeModule;

    @NonNull
    private final EventCreatorModule eventCreatorModule;

    @NonNull
    private final GossipModule gossipModule;

    @NonNull
    private final PcesModule pcesModule;

    @NonNull
    private final HashgraphModule hashgraphModule;

    @NonNull
    private final StatusMonitorModule statusMonitorModule;

    @NonNull
    private final FreezePeriodChecker freezePeriodChecker;

    public ConsensusLayerImpl(
            @NonNull final Configuration configuration,
            @Nullable final ConsensusSnapshot consensusSnapshot,
            @NonNull final EventIntakeModule eventIntakeModule,
            @NonNull final EventCreatorModule eventCreatorModule,
            @NonNull final GossipModule gossipModule,
            @NonNull final PcesModule pcesModule,
            @NonNull final HashgraphModule hashgraphModule,
            @NonNull final StatusMonitorModule statusMonitorModule,
            @NonNull final FreezePeriodChecker freezePeriodChecker) {
        this.configuration = requireNonNull(configuration);
        this.consensusSnapshot = consensusSnapshot;
        this.eventIntakeModule = requireNonNull(eventIntakeModule);
        this.eventCreatorModule = requireNonNull(eventCreatorModule);
        this.gossipModule = requireNonNull(gossipModule);
        this.pcesModule = requireNonNull(pcesModule);
        this.hashgraphModule = requireNonNull(hashgraphModule);
        this.statusMonitorModule = requireNonNull(statusMonitorModule);
        this.freezePeriodChecker = requireNonNull(freezePeriodChecker);
    }

    @Override
    public void start() {
        final long initialAncientThreshold = extractAncientThreshold(consensusSnapshot);
        final long startingRound = consensusSnapshot == null ? GENESIS_ROUND : consensusSnapshot.round();
        pcesModule.replayPcesEvents(initialAncientThreshold, startingRound);
        gossipModule.startInputWire().inject(NoInput.getInstance());
    }

    private static long extractAncientThreshold(@Nullable final ConsensusSnapshot consensusSnapshot) {
        if (consensusSnapshot == null) {
            return ConsensusConstants.ROUND_FIRST;
        }
        final List<MinimumJudgeInfo> minimumJudgeInfos = consensusSnapshot.minimumJudgeInfoList();
        if (minimumJudgeInfos.isEmpty()) {
            if (consensusSnapshot.round() == GENESIS_ROUND) {
                return ConsensusConstants.ROUND_FIRST;
            }
            throw new IllegalStateException(
                    String.format("No minimum judge info found in state for round %d, list is empty", consensusSnapshot.round()));
        }
        return minimumJudgeInfos.getFirst().minimumJudgeBirthRound();
    }

    @Override
    public void destroy() {}

    @Override
    public void requestNextRound(@Nullable final Roster newRoster, @Nullable final Instant freezeTime) {
        throwOnInvalidFreezeTime(freezeTime);
        freezePeriodChecker.setFreezeTime(freezeTime);
    }

    private void throwOnInvalidFreezeTime(@Nullable final Instant freezeTime) {
        if (freezeTime == null) {
            return;
        }
        // TODO if the freezeTime is before the latest consensus round, throw an exception
    }

    @Override
    public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
        statusMonitorModule.quiescenceCommandInputWire().inject(command);
        eventCreatorModule.quiescenceCommandInputWire().inject(command);
    }

    @Override
    public void oldestRestartableSnapshot(@NonNull final ConsensusSnapshot consensusSnapshot) {
        pcesModule.minimumBirthRoundInputWire().inject(extractAncientThreshold(consensusSnapshot));
    }

    @Override
    public void setReconnectPeerProtocolFactory(
            @NonNull final PeerProtocolFactory reconnectPeerProtocolFactory) {
        gossipModule.setReconnectPeerProtocolFactory(reconnectPeerProtocolFactory);
    }

    @Override
    public void onStatusUpdate(@NonNull final StatusUpdate status) {
        switch (status) {
            case FREEZE_COMPLETE -> statusMonitorModule.platformStatusActionInputWire().inject(new FreezeCompleteAction());
            case CATASTROPHIC_FAILURE -> statusMonitorModule.platformStatusActionInputWire().inject(new CatastrophicFailureAction());
            default -> throw new IllegalArgumentException("Unknown status: " + status);
        }
    }
}
