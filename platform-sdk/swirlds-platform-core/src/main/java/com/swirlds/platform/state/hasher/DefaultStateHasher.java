// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.hasher;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.eventhandling.StateWithHashComplexity;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Hashes signed states after all modifications for a round have been completed.
 */
public class DefaultStateHasher implements StateHasher {

    private final StateHasherMetrics metrics;

    /**
     * Constructs a SignedStateHasher to hash SignedStates.  If the signedStateMetrics object is not null, the time
     * spent hashing is recorded. Any fatal errors that occur are passed to the provided FatalErrorConsumer. The hash is
     * dispatched to the provided StateHashedTrigger.
     *
     * @param platformContext the platform context
     */
    public DefaultStateHasher(@NonNull final PlatformContext platformContext) {
        metrics = new StateHasherMetrics(platformContext.getMetrics());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public ReservedSignedState hashState(@NonNull final StateWithHashComplexity stateWithHashComplexity) {
        final ReservedSignedState reservedSignedState = stateWithHashComplexity.reservedSignedState();
        final Instant start = Instant.now();
        reservedSignedState.get().getState().computeHash();
        metrics.reportHashingTime(Duration.between(start, Instant.now()));

        return reservedSignedState;
    }
}
