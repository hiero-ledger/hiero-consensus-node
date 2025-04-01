// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import static com.swirlds.common.utility.CompareTo.isLessThanOrEqualTo;
import static org.hiero.consensus.event.creator.EventCreationStatus.OVERLOADED;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.hiero.consensus.event.creator.EventCreationRule;
import org.hiero.consensus.event.creator.EventCreationStatus;

/**
 * A rule that permits event creation only when the platform is considered to be in a healthy state.
 */
public class PlatformHealthRule implements EventCreationRule {

    private final Duration maximumPermissibleUnhealthyDuration;
    private final Supplier<Duration> currentUnhealthyDurationSupplier;

    /**
     * Constructor.
     *
     * @param maximumPermissibleUnhealthyDuration the maximum permissible duration that the platform can be unhealthy
     *                                            before this rule will prevent event creation
     * @param currentUnhealthyDurationSupplier    a supplier of the current unhealthy duration
     */
    public PlatformHealthRule(
            @NonNull final Duration maximumPermissibleUnhealthyDuration,
            @NonNull final Supplier<Duration> currentUnhealthyDurationSupplier) {

        this.maximumPermissibleUnhealthyDuration = Objects.requireNonNull(maximumPermissibleUnhealthyDuration);
        this.currentUnhealthyDurationSupplier = Objects.requireNonNull(currentUnhealthyDurationSupplier);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEventCreationPermitted() {
        return isLessThanOrEqualTo(currentUnhealthyDurationSupplier.get(), maximumPermissibleUnhealthyDuration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void eventWasCreated() {
        // do nothing
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
