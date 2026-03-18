// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl.signature;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.utility.throttle.RateLimitedLogger;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.intake.utils.EventSignatureChecker;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.roster.RosterHistory;

/**
 * Default implementation for verifying event signatures. Delegates the actual signature
 * verification to an {@link EventSignatureChecker} and handles pipeline-level concerns
 * (ancient checks, self-event bypass, intake counter, metrics).
 */
public class DefaultEventSignatureValidator implements EventSignatureValidator {
    private static final Logger logger = LogManager.getLogger(DefaultEventSignatureValidator.class);
    private static final Duration MINIMUM_LOG_PERIOD = Duration.ofMinutes(1);

    private final EventSignatureChecker signatureChecker;
    private final IntakeEventCounter intakeEventCounter;
    private final RateLimitedLogger rateLimitedLogger;
    private volatile EventWindow eventWindow = EventWindow.getGenesisEventWindow();

    private static final LongAccumulator.Config VALIDATION_FAILED_CONFIG = new LongAccumulator.Config(
                    PLATFORM_CATEGORY, "eventsFailedSignatureValidation")
            .withDescription("Events for which signature validation failed")
            .withUnit("events");
    private final LongAccumulator validationFailedAccumulator;

    /**
     * Constructor.
     *
     * @param metrics            the metrics system
     * @param time               the time source
     * @param signatureChecker   shared signature verification logic
     * @param intakeEventCounter keeps track of the number of events in the intake pipeline from each peer
     */
    public DefaultEventSignatureValidator(
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final EventSignatureChecker signatureChecker,
            @NonNull final IntakeEventCounter intakeEventCounter) {

        this.signatureChecker = Objects.requireNonNull(signatureChecker);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
        this.rateLimitedLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);
        this.validationFailedAccumulator = metrics.getOrCreate(VALIDATION_FAILED_CONFIG);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformEvent validateSignature(@NonNull final PlatformEvent event) {
        if (eventWindow.isAncient(event)) {
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
            return null;
        }

        if (event.getOrigin() == EventOrigin.RUNTIME) {
            return event;
        }

        if (signatureChecker.isSignatureValid(event)) {
            return event;
        } else {
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
            validationFailedAccumulator.update(1);
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Event failed signature check. Event: {}, Signature: {}, Hash: {}",
                    event,
                    event.getSignature().toHex(),
                    event.getHash());
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = Objects.requireNonNull(eventWindow);
        signatureChecker.evictAncient(eventWindow.ancientThreshold());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateRosterHistory(@NonNull final RosterHistory rosterHistory) {
        signatureChecker.setRosterHistory(rosterHistory);
    }
}