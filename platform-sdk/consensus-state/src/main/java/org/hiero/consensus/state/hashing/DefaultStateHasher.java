// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.hashing;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.signed.StateWithHashComplexity;

/**
 * Hashes signed states after all modifications for a round have been completed.
 */
public class DefaultStateHasher implements StateHasher {

    private static final Logger logger = LogManager.getLogger(DefaultStateHasher.class);

    private final StateHasherMetrics stateHasherMetrics;

    /**
     * Constructs a SignedStateHasher to hash SignedStates.  If the signedStateMetrics object is not null, the time
     * spent hashing is recorded. Any fatal errors that occur are passed to the provided FatalErrorConsumer. The hash is
     * dispatched to the provided StateHashedTrigger.
     *
     * @param metrics the metrics object
     */
    public DefaultStateHasher(@NonNull final Metrics metrics) {
        stateHasherMetrics = new StateHasherMetrics(metrics);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public ReservedSignedState hashState(@NonNull final StateWithHashComplexity stateWithHashComplexity) {
        final ReservedSignedState reservedSignedState = stateWithHashComplexity.reservedSignedState();
        final SignedState signedState = reservedSignedState.get();
        final Instant start = Instant.now();
        signedState.getState().getHash();
        final Duration hashingDuration = Duration.between(start, Instant.now());
        stateHasherMetrics.reportHashingTime(hashingDuration);
        if (signedState.isFreezeState()) {
            logger.info(
                    STARTUP.getMarker(),
                    "++++++++ Freeze state hash for round {} is calculated, took {} ms",
                    signedState.getRound(),
                    hashingDuration.toMillis());
        }

        return reservedSignedState;
    }
}
