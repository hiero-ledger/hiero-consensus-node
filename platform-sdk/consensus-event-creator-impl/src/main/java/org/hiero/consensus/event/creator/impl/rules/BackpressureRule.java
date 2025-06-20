// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import static org.hiero.consensus.event.creator.impl.EventCreationStatus.OVERLOADED;

import com.swirlds.common.context.PlatformContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.hiero.consensus.event.creator.impl.EventCreationStatus;
import org.hiero.consensus.event.creator.impl.config.EventCreationConfig;

/**
 * Prevents event creations when the system is stressed and unable to keep up with its work load.
 */
public class BackpressureRule implements EventCreationRule {

    /**
     * Prevent new events from being created if the event intake queue ever meets or exceeds this size.
     */
    private final int eventIntakeThrottle;

    private final LongSupplier eventIntakeQueueSize;

    /**
     * Constructor.
     *
     * @param platformContext      the platform's context
     * @param eventIntakeQueueSize provides the size of the event intake queue
     */
    public BackpressureRule(
            @NonNull final PlatformContext platformContext, @NonNull final LongSupplier eventIntakeQueueSize) {

        final EventCreationConfig eventCreationConfig =
                platformContext.getConfiguration().getConfigData(EventCreationConfig.class);

        eventIntakeThrottle = eventCreationConfig.eventIntakeThrottle();

        this.eventIntakeQueueSize = Objects.requireNonNull(eventIntakeQueueSize);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEventCreationPermitted() {
        return eventIntakeQueueSize.getAsLong() < eventIntakeThrottle;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void eventWasCreated() {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public EventCreationStatus getEventCreationStatus() {
        return OVERLOADED;
    }
}
