package org.hiero.consensus.hashgraph.impl;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.hashgraph.impl.DefaultConsensusEngineBuffer.ConsensusEngineBufferOutput;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.status.PlatformStatus;

public interface ConsensusEngineBuffer {


    @NonNull
    ConsensusEngineBufferOutput requestRound();

    @NonNull
    ConsensusEngineBufferOutput addEvent(@NonNull PlatformEvent event);

    void outOfBandSnapshotUpdate(@NonNull ConsensusSnapshot snapshot);

    void updatePlatformStatus(@NonNull PlatformStatus platformStatus);
}
