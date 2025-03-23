// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.nexus;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * A nexus that holds the latest complete signed state.
 */
public interface LatestCompleteStateNexus extends SignedStateNexus {

    /**
     * Update the current event window. May cause the latest complete state to be thrown away if it has been a long
     * time since a state has been completely signed.
     */
    @InputWireLabel("event window")
    void updateEventWindow(@NonNull EventWindow eventWindow);

    /**
     * Replace the current state with the given state if the given state is newer than the current state.
     *
     * @param reservedSignedState the new state
     */
    @InputWireLabel("complete state")
    void setStateIfNewer(@NonNull ReservedSignedState reservedSignedState);
}
