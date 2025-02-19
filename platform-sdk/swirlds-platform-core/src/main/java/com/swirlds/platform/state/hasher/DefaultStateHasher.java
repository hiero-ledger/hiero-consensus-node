/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.state.hasher;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.platform.wiring.components.StateAndRound;
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
            MerkleCryptoFactory.getInstance()
                    .digestTreeAsync(stateAndRound.reservedSignedState().get().getState())
                    .get();

            metrics.reportHashingTime(Duration.between(start, Instant.now()));

            return stateAndRound;
        } catch (final ExecutionException e) {
            logger.fatal(EXCEPTION.getMarker(), "Exception occurred during SignedState hashing", e);
        } catch (final InterruptedException e) {
            logger.error(EXCEPTION.getMarker(), "Interrupted while hashing state. Expect buggy behavior.");
            Thread.currentThread().interrupt();
        }
        return null;
    }
}
