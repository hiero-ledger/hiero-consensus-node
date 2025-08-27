// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import static org.hiero.consensus.event.creator.impl.EventCreationStatus.RATE_LIMITED;

import com.swirlds.base.time.Time;
import com.swirlds.common.utility.throttle.RateLimiter;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.event.creator.impl.EventCreationStatus;
import org.hiero.consensus.event.creator.impl.config.EventCreationConfig;

/**
 * Throttles event creation rate over time.
 */
public class MaximumRateRule implements EventCreationRule {

    private final RateLimiter rateLimiter;

    /**
     * Constructor.
     *
     * @param configuration provides the configuration for the event creator
     * @param time          provides the time source for rate limiting
     */
    public MaximumRateRule(@NonNull final Configuration configuration, @NonNull final Time time) {

        final EventCreationConfig eventCreationConfig = configuration.getConfigData(EventCreationConfig.class);

        final double maxCreationRate = eventCreationConfig.maxCreationRate();
        if (maxCreationRate > 0) {
            rateLimiter = new RateLimiter(time, maxCreationRate);
        } else {
            // No brakes!
            rateLimiter = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEventCreationPermitted() {
        if (rateLimiter != null) {
            return rateLimiter.request();
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void eventWasCreated() {
        if (rateLimiter != null) {
            rateLimiter.trigger();
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public EventCreationStatus getEventCreationStatus() {
        return RATE_LIMITED;
    }
}
