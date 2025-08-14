// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.consensus;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.Consensus;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Responsible for adding events to {@link Consensus}.
 */
public interface ConsensusEngine {

    /**
     * Update the platform status.
     *
     * @param platformStatus the new platform status
     */
    @InputWireLabel("PlatformStatus")
    void updatePlatformStatus(@NonNull PlatformStatus platformStatus);

    /**
     * Add an event to the consensus engine. This event might not be added to the hashgraph immediately, but it will be
     * processed and will be returned as part of the output if it's a pre-consensus event.
     *
     * @param event an event to be added
     * @return a list of rounds and a list of recent event waiting to reach consensus
     */
    @NonNull
    @InputWireLabel("PlatformEvent")
    ConsensusEngineOutput addEvent(@NonNull PlatformEvent event);

    /**
     * Perform an out-of-band snapshot update. This happens at restart/reconnect boundaries.
     *
     * @param snapshot the snapshot to adopt
     */
    void outOfBandSnapshotUpdate(@NonNull ConsensusSnapshot snapshot);
}
