// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.validation;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.platform.crypto.SignatureVerifier;
import com.swirlds.platform.eventhandling.EventConfig;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.state.lifecycle.HapiUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

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
     * The previous roster map. May be null.
     */
    private Map<Long, RosterEntry> previousRosterMap;

    /**
     * The current roster map.
     */
    private Map<Long, RosterEntry> currentRosterMap;

    /**
     * The current software version.
     */
    private final SemanticVersion currentSoftwareVersion;

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
     * @param currentSoftwareVersion the current software version
     * @param previousRoster    the previous address book
     * @param currentRoster     the current address book
     * @param intakeEventCounter     keeps track of the number of events in the intake pipeline from each peer
     */
    public DefaultEventSignatureValidator(
            @NonNull final PlatformContext platformContext,
            @NonNull final SignatureVerifier signatureVerifier,
            @NonNull final SemanticVersion currentSoftwareVersion,
            @Nullable final Roster previousRoster,
            @NonNull final Roster currentRoster,
            @NonNull final IntakeEventCounter intakeEventCounter) {

        this.signatureVerifier = Objects.requireNonNull(signatureVerifier);
        this.currentSoftwareVersion = Objects.requireNonNull(currentSoftwareVersion);
        this.previousRosterMap = RosterUtils.toMap(previousRoster);
        this.currentRosterMap = RosterUtils.toMap(Objects.requireNonNull(currentRoster));
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);

        this.rateLimitedLogger = new RateLimitedLogger(logger, platformContext.getTime(), MINIMUM_LOG_PERIOD);

        this.validationFailedAccumulator = platformContext.getMetrics().getOrCreate(VALIDATION_FAILED_CONFIG);

        eventWindow = EventWindow.getGenesisEventWindow(platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .getAncientMode());
    }

    /**
     * Determine whether the previous roster or the current roster should be used to verify an event's
     * signature.
     * <p>
     * Logs an error and returns null if an applicable roster cannot be selected
     *
     * @param event the event to be validated
     * @return the applicable roster map, or null if an applicable roster cannot be selected
     */
    @Nullable
    private Map<Long, RosterEntry> determineApplicableRosterMap(@NonNull final PlatformEvent event) {
        final SemanticVersion eventVersion = event.getSoftwareVersion();

        final int softwareComparison =
                HapiUtils.SEMANTIC_VERSION_COMPARATOR.compare(currentSoftwareVersion, eventVersion);
        if (softwareComparison < 0) {
            // current software version is less than event software version
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Cannot validate events for software version {} that is greater than the current software version {}",
                    eventVersion,
                    currentSoftwareVersion);
            return null;
        } else if (softwareComparison > 0) {
            // current software version is greater than event software version
            if (previousRosterMap == null) {
                rateLimitedLogger.error(
                        EXCEPTION.getMarker(),
                        "Cannot validate events for software version {} that is less than the current software version {} without a previous roster",
                        eventVersion,
                        currentSoftwareVersion);
                return null;
            }
            return previousRosterMap;
        } else {
            // current software version is equal to event software version
            return currentRosterMap;
        }
    }

    /**
     * Determine whether a given event has a valid signature.
     *
     * @param event the event to be validated
     * @return true if the event has a valid signature, otherwise false
     */
    private boolean isSignatureValid(@NonNull final PlatformEvent event) {
        final Map<Long, RosterEntry> applicableRosterMap = determineApplicableRosterMap(event);
        if (applicableRosterMap == null) {
            // this occurrence was already logged while attempting to determine the applicable roster
            return false;
        }

        final NodeId eventCreatorId = event.getCreatorId();

        if (!applicableRosterMap.containsKey(eventCreatorId.id())) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Node {} doesn't exist in applicable roster. Event: {}",
                    eventCreatorId,
                    event);
            return false;
        }

        final X509Certificate cert = RosterUtils.fetchGossipCaCertificate(applicableRosterMap.get(eventCreatorId.id()));
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
    public void updateRosters(@NonNull final RosterUpdate rosterUpdate) {
        this.previousRosterMap = RosterUtils.toMap(rosterUpdate.previousRoster());
        this.currentRosterMap = RosterUtils.toMap(rosterUpdate.currentRoster());
    }
}
