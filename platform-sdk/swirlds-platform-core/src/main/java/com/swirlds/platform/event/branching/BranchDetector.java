// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.branching;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Detects branching in the hashgraph.
 */
public interface BranchDetector {

    /**
     * Add an event to the branch detector. Events should be passed to this method in topological order.
     *
     * @param event the event to add
     * @return the event if it is a branching event or null if the event is not a branching event
     */
    @Nullable
    PlatformEvent checkForBranches(@NonNull PlatformEvent event);

    /**
     * Update the event window in the branch detector. This should be called whenever the event window is updated and
     * before the first event is added.
     *
     * @param eventWindow the new event window
     */
    @InputWireLabel("event window")
    void updateEventWindow(@NonNull EventWindow eventWindow);

    /**
     * Clear the branch detector, returning it to its initial state.
     */
    void clear();
}
