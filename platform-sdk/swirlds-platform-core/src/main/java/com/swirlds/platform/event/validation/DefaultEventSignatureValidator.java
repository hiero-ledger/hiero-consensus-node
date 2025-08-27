// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.validation;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.platform.crypto.SignatureVerifier;
import com.swirlds.platform.gossip.IntakeEventCounter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterEntryNotFoundException;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;

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
    private RosterHistory rosterHistory;

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
     * @param platformContext        the platform context
     * @param signatureVerifier      a verifier for checking event signatures
     * @param rosterHistory          the complete roster history
     * @param intakeEventCounter     keeps track of the number of events in the intake pipeline from each peer
     */
    public DefaultEventSignatureValidator(
            @NonNull final PlatformContext platformContext,
            @NonNull final SignatureVerifier signatureVerifier,
            @Nullable final RosterHistory rosterHistory,
            @NonNull final IntakeEventCounter intakeEventCounter) {

        this.signatureVerifier = Objects.requireNonNull(signatureVerifier);
        this.rosterHistory = Objects.requireNonNull(rosterHistory);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);

        this.rateLimitedLogger = new RateLimitedLogger(logger, platformContext.getTime(), MINIMUM_LOG_PERIOD);

        this.validationFailedAccumulator = platformContext.getMetrics().getOrCreate(VALIDATION_FAILED_CONFIG);

        eventWindow = EventWindow.getGenesisEventWindow();
    }

    /**
     * Determine whether a given event has a valid signature.
     *
     * @param event the event to be validated
     * @return true if the event has a valid signature, otherwise false
     */
    private boolean isSignatureValid(@NonNull final PlatformEvent event) {
        final Roster applicableRoster = rosterHistory.getRosterForRound(event.getBirthRound());
        if (applicableRoster == null) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Cannot validate events for birth round {} without a roster",
                    event.getBirthRound());
            return false;
        }
        final NodeId eventCreatorId = event.getCreatorId();
        final RosterEntry rosterEntry;
        try {
            rosterEntry = RosterUtils.getRosterEntry(applicableRoster, eventCreatorId.id());
        } catch (RosterEntryNotFoundException e) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Node {} doesn't exist in applicable roster. Event: {}",
                    eventCreatorId,
                    event);
            return false;
        }

        final X509Certificate cert = RosterUtils.fetchGossipCaCertificate(rosterEntry);

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
    public void updateRosterHistory(@NonNull final RosterHistory rosterHistory) {
        this.rosterHistory = Objects.requireNonNull(rosterHistory);
    }
}
