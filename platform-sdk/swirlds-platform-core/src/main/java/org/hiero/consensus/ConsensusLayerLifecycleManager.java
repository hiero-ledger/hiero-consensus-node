package org.hiero.consensus;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static org.hiero.consensus.platformstate.PlatformStateUtils.legacyRunningEventHashOf;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.function.Supplier;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.platformstate.PlatformStateUtils;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Responsible for managing the lifecycle of the consensus layer. It creates and recreates the consensus layer as needed
 * and remembers the previously provided inputs.
 */
public class ConsensusLayerLifecycleManager implements Supplier<ConsensusLayer> {

    private ConsensusLayerInputs consensusLayerInputs;
    private ConsensusLayer consensusLayer;

    public ConsensusLayerLifecycleManager(@NonNull final ConsensusLayerInputs consensusLayerInputs) {
        this.consensusLayerInputs = requireNonNull(consensusLayerInputs, "inputs must not be null");
    }

    /**
     * Creates a new instance of the consensus layer using the current inputs.
     */
    public void createConsensusLayer() {
        final ConsensusLayerFactory consensusLayerFactory = new ConsensusLayerFactory(consensusLayerInputs);
        consensusLayer = consensusLayerFactory.create();
    }

    /**
     * Creates a new instance of the consensus layer using the provided state and previously provided inputs. This
     * method is typically called when a reconnect occurs and the consensus layer needs to be recreated with the new
     * state.
     *
     * @param state the state to use when creating the new consensus layer
     */
    public void recreateConsensusLayer(@NonNull final ReservedSignedState state) {
        final ConsensusSnapshot consensusSnapshot = getInitialConsensusSnapshot(state);

        final Hash legacyRunningEventHash =
                requireNonNullElse(legacyRunningEventHashOf(state.get().getState()), Cryptography.NULL_HASH);
        final RunningEventHashOverride runningEventHashOverride =
                new RunningEventHashOverride(legacyRunningEventHash, false);

        consensusLayerInputs = consensusLayerInputs.copyWithNewValues(consensusLayerInputs, consensusSnapshot,
                runningEventHashOverride);

        createConsensusLayer();
    }

    private ConsensusSnapshot getInitialConsensusSnapshot(@NonNull final ReservedSignedState state) {
        return PlatformStateUtils.consensusSnapshotOf(state.get().getState());
    }

    @Override
    public ConsensusLayer get() {
        return consensusLayer;
    }
}
