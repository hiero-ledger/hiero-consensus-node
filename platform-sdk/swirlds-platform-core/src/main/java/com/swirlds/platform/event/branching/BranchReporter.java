// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.branching;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * This class is responsible for logging and producing metrics when a branch is observed.
 */
public interface BranchReporter {

    /**
     * Report a branching event.
     *
     * @param event the branching event
     */
    void reportBranch(@NonNull PlatformEvent event);

    /**
     * Update the event window.  This should be called whenever the event window is updated and before the first
     * branching event is reported.
     *
     * @param eventWindow the new event window
     */
    @InputWireLabel("event window")
    void updateEventWindow(@NonNull EventWindow eventWindow);

    /**
     * Clear the branch reporter, returning it to its initial state.
     */
    void clear();
}
