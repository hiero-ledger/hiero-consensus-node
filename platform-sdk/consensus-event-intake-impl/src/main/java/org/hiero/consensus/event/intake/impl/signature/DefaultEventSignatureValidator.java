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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.BytesSignatureVerifier;
import org.hiero.consensus.concurrent.utility.throttle.RateLimitedLogger;
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
     * Factory that creates a {@link BytesSignatureVerifier} for a given public key. The resulting
     * verifier instance is cached per (roster, node) so that the factory is only called once per
     * node per roster, avoiding repeated object creation and key extraction overhead.
     */
    private final Function<PublicKey, BytesSignatureVerifier> verifierFactory;

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
     * Cache of signature verifiers per roster and node ID. Avoids repeated roster entry lookups,
     * X.509 certificate parsing, public key extraction, and verifier object creation for every
     * event. The outer map is keyed by {@link Roster} reference identity (rosters from
     * {@link RosterHistory} are interned by hash), and the inner map is keyed by {@link NodeId}.
     *
     * <p>This cache is accessed concurrently since the event signature validator uses a
     * {@code CONCURRENT} task scheduler. The reference is replaced atomically on roster changes.
     *
     * <p>A sentinel {@link #MISSING_VERIFIER} value in the inner map means the verifier could
     * not be created for that node (missing roster entry, null certificate, etc.) and the lookup
     * should not be re-attempted until the roster changes.
     */
    private volatile ConcurrentMap<Roster, ConcurrentMap<NodeId, BytesSignatureVerifier>> verifierCache =
            new ConcurrentHashMap<>();

    /**
     * Sentinel value used in the cache to distinguish "we tried and failed to create a verifier"
     * from "we haven't tried yet". {@link ConcurrentHashMap} does not allow null values.
     */
    private static final BytesSignatureVerifier MISSING_VERIFIER = (data, signature) -> false;

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
     * @param verifierFactory        a factory that creates a {@link BytesSignatureVerifier} for a given public key
     * @param rosterHistory          the complete roster history
     * @param intakeEventCounter     keeps track of the number of events in the intake pipeline from each peer
     */
    public DefaultEventSignatureValidator(
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final Function<PublicKey, BytesSignatureVerifier> verifierFactory,
            @Nullable final RosterHistory rosterHistory,
            @NonNull final IntakeEventCounter intakeEventCounter) {

        this.verifierFactory = Objects.requireNonNull(verifierFactory);
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
        final BytesSignatureVerifier verifier = lookupVerifier(applicableRoster, eventCreatorId);
        if (verifier == null) {
            return false;
        }

        final boolean isSignatureValid =
                verifier.verify(event.getHash().getBytes(), event.getSignature());

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
     * Look up the cached signature verifier for a given node in the given roster. On the first
     * call for a (roster, node) pair, this resolves the public key from the roster and creates
     * a {@link BytesSignatureVerifier} via the factory. Subsequent calls return the cached instance.
     *
     * <p>This method is safe for concurrent access. {@link ConcurrentHashMap#computeIfAbsent}
     * guarantees that the factory is called at most once per key.
     *
     * @param roster the roster to look up the node in
     * @param nodeId the node ID to look up
     * @return the cached verifier, or null if the public key could not be resolved
     */
    @Nullable
    private BytesSignatureVerifier lookupVerifier(@NonNull final Roster roster, @NonNull final NodeId nodeId) {
        final ConcurrentMap<NodeId, BytesSignatureVerifier> rosterCache =
                verifierCache.computeIfAbsent(roster, k -> new ConcurrentHashMap<>());

        final BytesSignatureVerifier verifier =
                rosterCache.computeIfAbsent(nodeId, k -> {
                    final BytesSignatureVerifier created = createVerifier(roster, k);
                    return created != null ? created : MISSING_VERIFIER;
                });

        return verifier == MISSING_VERIFIER ? null : verifier;
    }

    /**
     * Resolve the public key for a given node from the roster and create a
     * {@link BytesSignatureVerifier} for it.
     *
     * @param roster the roster to look up the node in
     * @param nodeId the node ID to look up
     * @return a verifier for the node's public key, or null if it could not be resolved
     */
    @Nullable
    private BytesSignatureVerifier createVerifier(@NonNull final Roster roster, @NonNull final NodeId nodeId) {
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
            return null;
        }

        return verifierFactory.apply(publicKey);
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
        // Replace with a new map rather than clearing — concurrent readers still hold
        // a reference to the old map and can safely finish their lookups.
        this.verifierCache = new ConcurrentHashMap<>();
    }
}