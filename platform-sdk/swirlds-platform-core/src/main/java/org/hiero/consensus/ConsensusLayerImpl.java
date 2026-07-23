package org.hiero.consensus;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

public class ConsensusLayerImpl implements ConsensusLayer {

    @NonNull
    private final ConsensusLayerBuildingBlocks buildingBlocks;

    public ConsensusLayerImpl(@NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        this.buildingBlocks = requireNonNull(buildingBlocks);
    }

    @Override
    public void start() {

    }

    @Override
    public void destroy() {

    }

    @Override
    public void nextRound(Roster newRoster) {

    }

    @Override
    public void quiescenceCommand(@NonNull final QuiescenceCommand command) {
        buildingBlocks.statusMonitorModule().submitQuiescenceCommand(command);
        buildingBlocks.eventCreatorModule().quiescenceCommandInputWire().inject(command);
    }
}
