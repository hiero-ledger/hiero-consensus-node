// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.creation;

import static org.hiero.event.creator.EventCreationStatus.ATTEMPTING_CREATION;
import static org.hiero.event.creator.EventCreationStatus.IDLE;
import static org.hiero.event.creator.EventCreationStatus.NO_ELIGIBLE_PARENTS;
import static org.hiero.event.creator.EventCreationStatus.RATE_LIMITED;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.extensions.PhaseTimer;
import com.swirlds.common.metrics.extensions.PhaseTimerBuilder;
import com.swirlds.platform.event.creation.rules.PlatformStatusRule;
import com.swirlds.platform.pool.TransactionPoolNexus;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.event.creator.EventCreationRule;
import org.hiero.event.creator.EventCreationStatus;
import org.hiero.event.creator.impl.EventCreationConfig;
import org.hiero.event.creator.impl.rules.AggregateEventCreationRules;
import org.hiero.event.creator.impl.rules.MaximumRateRule;
import org.hiero.event.creator.impl.rules.PlatformHealthRule;

/**
 * Default implementation of the {@link EventCreationManager}.
 */
public class DefaultEventCreationManager implements EventCreationManager {

    /**
     * Creates events.
     */
    private final EventCreator creator;

    /**
     * Rules that say if event creation is permitted.
     */
    private final EventCreationRule eventCreationRules;

    /**
     * Tracks the current phase of event creation.
     */
    private final PhaseTimer<EventCreationStatus> phase;

    /**
     * The current platform status.
     */
    private PlatformStatus platformStatus;

    /**
     * The duration that the system has been unhealthy.
     */
    private Duration unhealthyDuration = Duration.ZERO;

    /**
     * Constructor.
     *
     * @param platformContext      the platform context
     * @param transactionPoolNexus provides transactions to be added to new events
     * @param creator              creates events
     */
    public DefaultEventCreationManager(
            @NonNull final PlatformContext platformContext,
            @NonNull final TransactionPoolNexus transactionPoolNexus,
            @NonNull final EventCreator creator) {

        this.creator = Objects.requireNonNull(creator);

        final EventCreationConfig config = platformContext.getConfiguration().getConfigData(EventCreationConfig.class);

        final List<EventCreationRule> rules = new ArrayList<>();
        rules.add(new MaximumRateRule(platformContext));
        rules.add(new PlatformStatusRule(this::getPlatformStatus, transactionPoolNexus));
        rules.add(new PlatformHealthRule(config.maximumPermissibleUnhealthyDuration(), this::getUnhealthyDuration));

        this.eventCreationRules = AggregateEventCreationRules.of(rules);

        phase = new PhaseTimerBuilder<>(
                        platformContext, platformContext.getTime(), "platform", EventCreationStatus.class)
                .enableFractionalMetrics()
                .setInitialPhase(IDLE)
                .setMetricsNamePrefix("eventCreation")
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public UnsignedEvent maybeCreateEvent() {
        if (!eventCreationRules.isEventCreationPermitted()) {
            phase.activatePhase(eventCreationRules.getEventCreationStatus());
            return null;
        }

        phase.activatePhase(ATTEMPTING_CREATION);

        final UnsignedEvent newEvent = creator.maybeCreateEvent();
        if (newEvent == null) {
            // The only reason why the event creator may choose not to create an event
            // is if there are no eligible parents.
            phase.activatePhase(NO_ELIGIBLE_PARENTS);
        } else {
            eventCreationRules.eventWasCreated();
            // We created an event, we won't be allowed to create another until some time has elapsed.
            phase.activatePhase(RATE_LIMITED);
        }

        return newEvent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerEvent(@NonNull final PlatformEvent event) {
        creator.registerEvent(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        creator.setEventWindow(eventWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        creator.clear();
        phase.activatePhase(IDLE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        this.platformStatus = Objects.requireNonNull(platformStatus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        unhealthyDuration = Objects.requireNonNull(duration);
    }

    /**
     * Get the current platform status.
     *
     * @return the current platform status
     */
    @NonNull
    private PlatformStatus getPlatformStatus() {
        return platformStatus;
    }

    /**
     * Get the duration that the system has been unhealthy.
     *
     * @return the duration that the system has been unhealthy
     */
    private Duration getUnhealthyDuration() {
        return unhealthyDuration;
    }
}
