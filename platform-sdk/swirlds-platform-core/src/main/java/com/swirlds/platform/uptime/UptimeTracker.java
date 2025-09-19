// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.uptime;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

public interface UptimeTracker {
    /**
     * Look at the events in a round to determine which nodes are up and which nodes are down.
     *
     * @param round       the round to analyze
     */
    @InputWireLabel("monitor consensus round")
    void trackRound(@NonNull ConsensusRound round);
}
