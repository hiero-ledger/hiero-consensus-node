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
     * Add an event to the consensus engine. One of the following will be done with the event:
     * <ul>
     *     <li>The event might be added to the hashgraph and returned as a pre-consensus event</li>
     *     <li>The event might be ancient, in which case it is a future event</li>
     *     <li>The event might be a future event, in which case it won't be added to the hashgraph immediately. It will
     *     be added at some point in the future, when consensus advances by adding more events. Once its added, it will
     *     be returned as a pre-consensus event</li>
     * </ul>
     * The {@link ConsensusEngineOutput} guarantees the following:
     * <ul>
     *     <li>Each event that is added to the hashgraph will be returned as a pre-consensus event</li>
     *     <li>Each pre-consensus event must eventually reach consensus or become stale (the stale part will be
     *     implemented soon)</li>
     *     <li>Each event that reaches consensus or becomes stale, must have been previously returned as a pre-consensus
     *     event</li>
     * </ul>
     *
     * <b>NOTE: </b> The above stated guarantees are reset when {@link #outOfBandSnapshotUpdate(ConsensusSnapshot)}
     *
     * @param event an event to be added
     * @return the consensus output
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
