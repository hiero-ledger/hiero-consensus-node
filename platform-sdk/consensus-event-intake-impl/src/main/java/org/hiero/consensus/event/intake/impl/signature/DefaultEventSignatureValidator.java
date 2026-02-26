// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl.signature;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.utility.throttle.RateLimitedLogger;
import org.hiero.consensus.crypto.SignatureVerifier;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.model.event.EventOrigin;
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

    /**
     * Cache of public keys per roster and node ID. Avoids repeated roster entry lookups,
     * X.509 certificate parsing, and public key extraction for every event. The outer map is
     * keyed by {@link Roster} reference identity (rosters from {@link RosterHistory} are
     * interned by hash), and the inner map is keyed by {@link NodeId}.
     *
     * <p>A {@code null} value in the inner map means the public key could not be resolved
     * for that node (missing roster entry, null certificate, etc.) and the lookup should
     * not be re-attempted until the roster changes.
     */
    private final Map<Roster, Map<NodeId, PublicKey>> publicKeyCache = new HashMap<>();

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
            @Nullable final RosterHistory rosterHistory,
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
        final Roster applicableRoster = rosterHistory.getRosterForRound(event.getBirthRound());
        if (applicableRoster == null) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Cannot validate events for birth round {} without a roster",
                    event.getBirthRound());
            return false;
        }

        final NodeId eventCreatorId = event.getCreatorId();
        final PublicKey publicKey = lookupPublicKey(applicableRoster, eventCreatorId);
        if (publicKey == null) {
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
     * Look up the public key for a given node in the given roster, using the cache to avoid
     * repeated roster entry lookups, X.509 certificate parsing, and public key extraction.
     *
     * @param roster the roster to look up the node in
     * @param nodeId the node ID to look up
     * @return the public key, or null if it could not be resolved
     */
    @Nullable
    private PublicKey lookupPublicKey(@NonNull final Roster roster, @NonNull final NodeId nodeId) {
        final Map<NodeId, PublicKey> rosterCache =
                publicKeyCache.computeIfAbsent(roster, k -> new HashMap<>());

        if (rosterCache.containsKey(nodeId)) {
            return rosterCache.get(nodeId);
        }

        final PublicKey publicKey = resolvePublicKey(roster, nodeId);
        rosterCache.put(nodeId, publicKey);
        return publicKey;
    }

    /**
     * Resolve the public key for a given node from the roster by looking up the roster entry,
     * decoding the X.509 certificate, and extracting the public key.
     *
     * @param roster the roster to look up the node in
     * @param nodeId the node ID to look up
     * @return the public key, or null if it could not be resolved
     */
    @Nullable
    private PublicKey resolvePublicKey(@NonNull final Roster roster, @NonNull final NodeId nodeId) {
        final RosterEntry rosterEntry;
        try {
            rosterEntry = RosterUtils.getRosterEntry(roster, nodeId.id());
        } catch (RosterEntryNotFoundException e) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Node {} doesn't exist in applicable roster",
                    nodeId);
            return null;
        }

        final X509Certificate cert = RosterUtils.fetchGossipCaCertificate(rosterEntry);
        final PublicKey publicKey = cert == null ? null : cert.getPublicKey();

        if (publicKey == null) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(), "Cannot find publicKey for creator with ID: {}", nodeId);
        }

        return publicKey;
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
    public void updateRosterHistory(@NonNull final RosterHistory rosterHistory) {
        this.rosterHistory = Objects.requireNonNull(rosterHistory);
        this.publicKeyCache.clear();
    }
}
