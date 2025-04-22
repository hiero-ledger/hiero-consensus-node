// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.stale;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.transformers.RoutableData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.StaleEventDetectorOutput;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Detects when a self event becomes stale. This utility does not pay attention to events created by other nodes. This
 * utility may not observe a self event go stale if the node needs to reconnect or restart.
 */
public interface StaleEventDetector {

    /**
     * Add a newly created self event to the detector. If this event goes stale without this node restarting or
     * reconnecting, then the detector will observe the event go stale and take appropriate action.
     * <p>
     * Events added to this method "pass through" and are always returned as part of the output (even if stale).
     *
     * @param event the self event to add
     * @return a list of routable data, produces two output wires which can be split by a router. The first stream
     * corresponds to the tag {@link StaleEventDetectorOutput#SELF_EVENT} and contains all self events added to this
     * component. The second stream corresponds to the tag {@link StaleEventDetectorOutput#STALE_SELF_EVENT} and
     * contains all self events that have gone stale.
     */
    @InputWireLabel("self events")
    @NonNull
    List<RoutableData<StaleEventDetectorOutput>> addSelfEvent(@NonNull PlatformEvent event);

    /**
     * Add a round that has just reached consensus.
     *
     * @param consensusRound a round that has just reached consensus
     * @return a list of routable data, produces two output wires which can be split by a router. The first stream
     * corresponds to the tag {@link StaleEventDetectorOutput#SELF_EVENT} and contains all self events added to this
     * component. The second stream corresponds to the tag {@link StaleEventDetectorOutput#STALE_SELF_EVENT} and
     * contains all self events that have gone stale.
     */
    @InputWireLabel("rounds")
    @NonNull
    List<RoutableData<StaleEventDetectorOutput>> addConsensusRound(@NonNull ConsensusRound consensusRound);

    /**
     * Set the initial event window for the detector. Must be called after restart and reconnect.
     *
     * @param initialEventWindow the initial event window
     */
    void setInitialEventWindow(@NonNull EventWindow initialEventWindow);

    /**
     * Clear the internal state of the detector.
     */
    void clear();
}
