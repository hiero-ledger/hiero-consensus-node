// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.consensus;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.system.events.CesEvent;
import com.swirlds.platform.system.status.PlatformStatus;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

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
     * Add an event to the hashgraph
     *
     * @param event an event to be added
     * @return a list of rounds that came to consensus as a result of adding the event
     */
    @NonNull
    @InputWireLabel("PlatformEvent")
    List<ConsensusRound> addEvent(@NonNull PlatformEvent event);

    /**
     * Perform an out-of-band snapshot update. This happens at restart/reconnect boundaries.
     *
     * @param snapshot the snapshot to adopt
     */
    void outOfBandSnapshotUpdate(@NonNull ConsensusSnapshot snapshot);

    /**
     * Extract a list of events intended for the consensus events stream
     *
     * @return a list of CES events
     */
    @NonNull
    default List<CesEvent> getCesEvents(@NonNull final ConsensusRound round) {
        return round.getStreamedEvents();
    }
}
