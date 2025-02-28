// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.hasher;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.wiring.components.StateAndRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
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
    public StateAndRound hashState(@NonNull final StateAndRound stateAndRound) {
        final Instant start = Instant.now();
        try {
            final Configuration configuration =
                    DefaultConfiguration.buildBasicConfiguration(ConfigurationBuilder.create());
            final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(configuration);
            merkleCryptography
                    .digestTreeAsync(
                            stateAndRound.reservedSignedState().get().getState().getRoot())
                    .get();

            metrics.reportHashingTime(Duration.between(start, Instant.now()));

            return stateAndRound;
        } catch (final ExecutionException e) {
            logger.fatal(EXCEPTION.getMarker(), "Exception occurred during SignedState hashing", e);
        } catch (final InterruptedException e) {
            logger.error(EXCEPTION.getMarker(), "Interrupted while hashing state. Expect buggy behavior.");
            Thread.currentThread().interrupt();
        } catch (final IOException e) {
            logger.fatal(EXCEPTION.getMarker(), "Fatal error occurred during building configuration", e);
        }
        return null;
    }
}
