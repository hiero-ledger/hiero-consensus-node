// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

public class ConsensusLayerImpl implements ConsensusLayer {

    @NonNull
    private final ConsensusLayerAdapterBuildingBlocks buildingBlocks;

    public ConsensusLayerImpl(@NonNull final ConsensusLayerAdapterBuildingBlocks buildingBlocks) {
        this.buildingBlocks = requireNonNull(buildingBlocks);
    }

    @Override
    public void start() {}

    @Override
    public void destroy() {}

    @Override
    public void requestNextRound(@Nullable final Roster newRoster, @Nullable final Instant freezeTime) {
        throwOnInvalidFreezeTime(freezeTime);
        buildingBlocks.freezePeriodChecker().setFreezeTime(freezeTime);
    }

    private void throwOnInvalidFreezeTime(@Nullable final Instant freezeTime) {
        if (freezeTime == null) {
            return;
        }
        // TODO if the freezeTime is before the latest consensus round, throw an exception
    }

    @Override
    public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
        buildingBlocks.statusMonitorModule().quiescenceCommandInputWire().inject(command);
        buildingBlocks.eventCreatorModule().quiescenceCommandInputWire().inject(command);
    }
}
