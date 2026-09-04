package org.hiero.consensus;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static org.hiero.consensus.platformstate.PlatformStateUtils.legacyRunningEventHashOf;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.platformstate.PlatformStateUtils;
import org.hiero.consensus.state.signed.ReservedSignedState;

public class ConsensusLayerLifecycleManager {

    private ConsensusLayerInputs consensusLayerInputs;
    private ConsensusLayer consensusLayer;

    public ConsensusLayerLifecycleManager(@NonNull final ConsensusLayerInputs consensusLayerInputs) {
        this.consensusLayerInputs = requireNonNull(consensusLayerInputs, "inputs must not be null");
    }

    public void createConsensusLayer() {
        final ConsensusLayerFactory consensusLayerFactory = new ConsensusLayerFactory(consensusLayerInputs);
        consensusLayer = consensusLayerFactory.create();
    }

    public void recreateConsensusLayer(@NonNull final ReservedSignedState state) {
        final ConsensusSnapshot consensusSnapshot = getInitialConsensusSnapshot(state);

        final Hash legacyRunningEventHash =
                requireNonNullElse(legacyRunningEventHashOf(state.get().getState()), Cryptography.NULL_HASH);
        final RunningEventHashOverride runningEventHashOverride =
                new RunningEventHashOverride(legacyRunningEventHash, false);

        consensusLayerInputs = consensusLayerInputs.copyWithNewValues(consensusLayerInputs, consensusSnapshot, runningEventHashOverride);

        createConsensusLayer();
    }

    private ConsensusSnapshot getInitialConsensusSnapshot(@NonNull final ReservedSignedState state) {
        return PlatformStateUtils.consensusSnapshotOf(state.get().getState());
    }

    public ConsensusLayer getConsensusLayer() {
        return consensusLayer;
    }

    @Nullable
    private Instant getFreezeTime(@NonNull final ReservedSignedState state) {
        final VirtualMapState root = state.get().getState();
        final Instant freezeTime = PlatformStateUtils.freezeTimeOf(root);
        final Instant lastFrozenTime = PlatformStateUtils.lastFrozenTimeOf(root);
        final Instant initialStateConsensusTime = PlatformStateUtils.consensusTimestampOf(root);
        if (initialStateConsensusTime != null && PlatformStateUtils.isInFreezePeriod(initialStateConsensusTime,
                freezeTime, lastFrozenTime)) {
            return freezeTime;
        }
        return null;
    }
}
