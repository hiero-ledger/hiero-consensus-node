// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.nexus;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.wiring.framework.component.InputWireLabel;

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
     * Replace the current state with the given state if the given state is newer than the current state. When
     * asynchronous snapshots are enabled, a freeze state instead releases the current state and prevents this nexus
     * from retaining any subsequent state.
     *
     * @param reservedSignedState the new state
     */
    @InputWireLabel("complete state")
    void setStateIfNewer(@NonNull ReservedSignedState reservedSignedState);

    /**
     * Observe a state before it is routed to the snapshot manager. An asynchronous freeze state releases the latest
     * complete state and prevents this nexus from retaining any subsequent state.
     *
     * <p>This method takes ownership of the reservation and always closes it.
     *
     * @param reservedSignedState the state to inspect
     */
    @InputWireLabel("async freeze state observer")
    void observeStateForAsyncFreeze(@NonNull ReservedSignedState reservedSignedState);

    /**
     * Update the current platform status. If the platform enters {@link PlatformStatus#FREEZING}, the latest complete
     * state is released. When asynchronous snapshots are enabled, no subsequent states are retained by this nexus.
     *
     * @param platformStatus the new platform status
     */
    @InputWireLabel("platform status")
    void updatePlatformStatus(@NonNull PlatformStatus platformStatus);
}
