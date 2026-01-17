// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.RuntimeObjectRecord;
import com.swirlds.common.utility.RuntimeObjectRegistry;
import com.swirlds.common.utility.throttle.RateLimiter;
import com.swirlds.platform.config.StateConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.CompareTo;

/**
 * This object is responsible for observing the lifespans of signed states, and taking action if a state suspected of a
 * memory leak is observed.
 */
public class DefaultSignedStateSentinel implements SignedStateSentinel {

    private static final Logger logger = LogManager.getLogger(DefaultSignedStateSentinel.class);

    private final RateLimiter rateLimiter;

    private final Duration maxSignedStateAge;

    /**
     * Create an object that monitors signed state lifespans.
     *
     * @param platformContext the current platform's context
     */
    public DefaultSignedStateSentinel(@NonNull final PlatformContext platformContext) {
        final StateConfig stateConfig = platformContext.getConfiguration().getConfigData(StateConfig.class);
        maxSignedStateAge = stateConfig.suspiciousSignedStateAgeGap();
        final Duration rateLimitPeriod = stateConfig.signedStateAgeNotifyRateLimit();
        rateLimiter = new RateLimiter(Time.getCurrent(), rateLimitPeriod);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkSignedStates(@NonNull final Instant now) {
        if (!rateLimiter.request()) {
            return;
        }
        final RuntimeObjectRecord oldest = RuntimeObjectRegistry.getOldestActiveObjectRecord(SignedState.class);
        if (oldest == null) {
            return;
        }
        final RuntimeObjectRecord newest = RuntimeObjectRegistry.getNewestActiveObjectRecord(SignedState.class);
        if (newest == null) {
            return;
        }

        final Duration signedStateGap = Duration.between(oldest.getCreationTime(), newest.getCreationTime());

        if (CompareTo.isGreaterThan(signedStateGap, maxSignedStateAge) && rateLimiter.requestAndTrigger()) {
            final SignedStateHistory history = oldest.getMetadata();
            logger.error(
                    EXCEPTION.getMarker(),
                    "Old signed state detected. The most likely causes are either that the node has gotten stuck or that there has been a memory leak.\n{}",
                    history);
        }
    }
}
