// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl.validation;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.validation.EventFieldValidator;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A default implementation of the {@link InternalEventValidator} interface that delegates field
 * validation to an {@link EventFieldValidator} and tracks rejected events via
 * {@link IntakeEventCounter}.
 */
public class DefaultInternalEventValidator implements InternalEventValidator {

    private final EventFieldValidator eventFieldValidator;
    private final IntakeEventCounter intakeEventCounter;

    /**
     * Constructor.
     *
     * @param eventFieldValidator validates internal event fields
     * @param intakeEventCounter  keeps track of the number of events in the intake pipeline from each peer
     */
    public DefaultInternalEventValidator(
            @NonNull final EventFieldValidator eventFieldValidator,
            @NonNull final IntakeEventCounter intakeEventCounter) {
        this.eventFieldValidator = Objects.requireNonNull(eventFieldValidator);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformEvent validateEvent(@NonNull final PlatformEvent event) {
        if (eventFieldValidator.isValid(event)) {
            return event;
        } else {
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
            return null;
        }
    }
}
