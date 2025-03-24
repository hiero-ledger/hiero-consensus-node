// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.hasher;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.platform.eventhandling.StateWithHashComplexity;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Hashes signed states after all modifications for a round have been completed.
 */
public class DefaultStateHasher implements StateHasher {

    private static final Logger logger = LogManager.getLogger(DefaultStateHasher.class);
    private final MerkleCryptography merkleCryptography;
    private final StateHasherMetrics metrics;

    /**
     * Constructs a SignedStateHasher to hash SignedStates.  If the signedStateMetrics object is not null, the time
     * spent hashing is recorded. Any fatal errors that occur are passed to the provided FatalErrorConsumer. The hash is
     * dispatched to the provided StateHashedTrigger.
     *
     * @param platformContext the platform context
     */
    public DefaultStateHasher(@NonNull final PlatformContext platformContext) {
        merkleCryptography = platformContext.getMerkleCryptography();
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
        try {
            merkleCryptography
                    .digestTreeAsync(reservedSignedState.get().getState().getRoot())
                    .get();
            metrics.reportHashingTime(Duration.between(start, Instant.now()));

            return reservedSignedState;
        } catch (final ExecutionException e) {
            logger.fatal(EXCEPTION.getMarker(), "Exception occurred during SignedState hashing", e);
        } catch (final InterruptedException e) {
            logger.error(EXCEPTION.getMarker(), "Interrupted while hashing state. Expect buggy behavior.");
            Thread.currentThread().interrupt();
        }
        return null;
    }
}
