// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl.signature;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.throttle.RateLimitedLogger;
import org.hiero.base.crypto.SignatureVerifier;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterEntryWrapper;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.roster.RosterWrapperHistory;

/**
 * Default implementation for verifying event signatures
 */
public class DefaultEventSignatureValidator implements EventSignatureValidator {
    private static final Logger logger = LogManager.getLogger(DefaultEventSignatureValidator.class);

    /**
     * The minimum period between log messages reporting a specific type of validation failure
     */
    private static final Duration MINIMUM_LOG_PERIOD = Duration.ofMinutes(1);

    /**
     * A verifier for checking event signatures.
     */
    private final SignatureVerifier signatureVerifier;

    /**
     * The complete roster history, i.e. all rosters for non-ancient rounds.
     */
    private RosterWrapperHistory rosterHistory;

    /**
     * The current event window.
     */
    private EventWindow eventWindow;

    /**
     * Keeps track of the number of events in the intake pipeline from each peer
     */
    private final IntakeEventCounter intakeEventCounter;

    /**
     * A logger for validation errors
     */
    private final RateLimitedLogger rateLimitedLogger;

    private static final LongAccumulator.Config VALIDATION_FAILED_CONFIG = new LongAccumulator.Config(
                    PLATFORM_CATEGORY, "eventsFailedSignatureValidation")
            .withDescription("Events for which signature validation failed")
            .withUnit("events");
    private final LongAccumulator validationFailedAccumulator;

    /**
     * Constructor
     *
     * @param metrics                the metrics system
     * @param time                   the time source
     * @param signatureVerifier      a verifier for checking event signatures
     * @param rosterHistory          the complete roster history
     * @param intakeEventCounter     keeps track of the number of events in the intake pipeline from each peer
     */
    public DefaultEventSignatureValidator(
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final SignatureVerifier signatureVerifier,
            @Nullable final RosterWrapperHistory rosterHistory,
            @NonNull final IntakeEventCounter intakeEventCounter) {

        this.signatureVerifier = Objects.requireNonNull(signatureVerifier);
        this.rosterHistory = Objects.requireNonNull(rosterHistory);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);

        this.rateLimitedLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);

        this.validationFailedAccumulator = metrics.getOrCreate(VALIDATION_FAILED_CONFIG);

        eventWindow = EventWindow.getGenesisEventWindow();
    }

    /**
     * Determine whether a given event has a valid signature.
     *
     * @param event the event to be validated
     * @return true if the event has a valid signature, otherwise false
     */
    private boolean isSignatureValid(@NonNull final PlatformEvent event) {
        final NodeId eventCreatorId = event.getCreatorId();
        final RosterEntryWrapper rosterEntry;
        try {
            final RosterWrapper applicableRoster = rosterHistory.rosterForRound(event.getBirthRound());
            rosterEntry = applicableRoster.getRosterEntry(eventCreatorId);
        } catch (final IllegalArgumentException e) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Cannot find roster entry for event with birth round {} and creator ID {}. Event: {}",
                    event.getBirthRound(),
                    eventCreatorId,
                    event);
            return false;
        }

        final X509Certificate cert = rosterEntry.gossipCaCertificate();

        final PublicKey publicKey = cert == null ? null : cert.getPublicKey();
        if (publicKey == null) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(), "Cannot find publicKey for creator with ID: {}", eventCreatorId);
            return false;
        }

        final boolean isSignatureValid =
                signatureVerifier.verifySignature(event.getHash().getBytes(), event.getSignature(), publicKey);

        if (!isSignatureValid) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Event failed signature check. Event: {}, Signature: {}, Hash: {}",
                    event,
                    event.getSignature().toHex(),
                    event.getHash());
        }

        return isSignatureValid;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformEvent validateSignature(@NonNull final PlatformEvent event) {
        if (eventWindow.isAncient(event)) {
            // ancient events can be safely ignored
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
            return null;
        }

        if (event.getOrigin() == EventOrigin.RUNTIME) {
            // This is an event we just created and signed, there is no need to validate the signature
            return event;
        }

        if (isSignatureValid(event)) {
            return event;
        } else {
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
            validationFailedAccumulator.update(1);

            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = Objects.requireNonNull(eventWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateRosterHistory(@NonNull final RosterWrapperHistory rosterHistory) {
        this.rosterHistory = Objects.requireNonNull(rosterHistory);
    }
}
