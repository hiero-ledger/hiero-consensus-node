// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.nexus;

import com.swirlds.common.utility.Clearable;
import com.swirlds.component.framework.component.SchedulerLabel;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;

/**
 * A thread-safe container that also manages reservations for a single signed state.
 */
@SchedulerLabel("LatestImmutableStateNexus")
public interface SignedStateNexus extends Clearable {
    /**
     * Returns the current signed state and reserves it. If the current signed state is null, or cannot be reserved,
     * then null is returned.
     *
     * @param reason a short description of why this SignedState is being reserved
     * @return the current signed state, or null if there is no state set or if it cannot be reserved
     */
    @Nullable
    ReservedSignedState getState(@NonNull String reason);

    /**
     * Sets the current signed state to the given state, and releases the previous state if it exists.
     *
     * @param reservedSignedState the new signed state
     */
    void setState(@Nullable ReservedSignedState reservedSignedState);

    /**
     * Returns the round of the current signed state
     *
     * @return the round of the current signed state, or {@link ConsensusConstants#ROUND_UNDEFINED} if there is no
     * current signed state
     */
    long getRound();

    /**
     * Same as {@link #setState(ReservedSignedState)} with a null argument
     */
    @Override
    default void clear() {
        setState(null);
    }
}
