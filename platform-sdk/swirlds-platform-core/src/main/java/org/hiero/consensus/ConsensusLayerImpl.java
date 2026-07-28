package org.hiero.consensus;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
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
    public void requestNextRound(@Nullable final Roster newRoster, @Nullable final Instant freezeTime) {
        // TODO update the freeze period checker in the hashgraph module with the freeze time if non-null
    }

    @Override
    public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
        buildingBlocks.statusMonitorModule().submitQuiescenceCommand(command);
        buildingBlocks.eventCreatorModule().quiescenceCommandInputWire().inject(command);
    }
}
