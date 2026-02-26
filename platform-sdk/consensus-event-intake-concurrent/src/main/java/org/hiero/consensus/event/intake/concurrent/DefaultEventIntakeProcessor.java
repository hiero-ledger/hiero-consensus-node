// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.concurrent;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_2;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.BytesSignatureVerifier;
import org.hiero.consensus.concurrent.utility.throttle.RateLimitedLogger;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.metrics.RunningAverageMetric;
import org.hiero.consensus.metrics.extensions.CountPerSecond;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterEntryNotFoundException;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Default implementation of {@link EventIntakeProcessor}. Combines hashing, field validation,
 * deduplication, and signature verification into a single concurrent component.
 *
 * <p>All shared state is accessed through thread-safe structures ({@link ConcurrentHashMap},
 * {@code volatile} fields), making this component safe for use with a {@code CONCURRENT}
 * task scheduler.
 */
public class DefaultEventIntakeProcessor implements EventIntakeProcessor {
    private static final Logger logger = LogManager.getLogger(DefaultEventIntakeProcessor.class);

    private static final Duration MINIMUM_LOG_PERIOD = Duration.ofMinutes(1);

    // --- Hashing (thread-local internally, no shared state) ---
    private final EventHasher eventHasher;

    // --- Validation (stateless, composes existing validator) ---
    private final EventFieldValidator eventFieldValidator;

    // --- Deduplication state ---
    private final ConcurrentHashMap<EventDescriptorWrapper, Set<Bytes>> observedEvents =
            new ConcurrentHashMap<>(1024);

    // --- Signature verification state ---
    private final Function<PublicKey, BytesSignatureVerifier> verifierFactory;
    private volatile RosterHistory rosterHistory;
    private volatile ConcurrentMap<Roster, ConcurrentMap<NodeId, BytesSignatureVerifier>> verifierCache =
            new ConcurrentHashMap<>();

    /**
     * Sentinel value used in the verifier cache to distinguish "we tried and failed to create
     * a verifier" from "we haven't tried yet". {@link ConcurrentHashMap} does not allow null values.
     */
    private static final BytesSignatureVerifier MISSING_VERIFIER = (data, signature) -> false;

    // --- Shared ---
    private volatile EventWindow eventWindow = EventWindow.getGenesisEventWindow();
    private final IntakeEventCounter intakeEventCounter;

    // --- Pipeline delay tracking (null when metrics are disabled) ---
    @Nullable
    private final EventPipelineTracker pipelineTracker;

    // --- Signature verification metrics ---
    private final RateLimitedLogger rateLimitedLogger;

    private static final LongAccumulator.Config SIG_VALIDATION_FAILED_CONFIG = new LongAccumulator.Config(
                    PLATFORM_CATEGORY, "eventsFailedSignatureValidation")
            .withDescription("Events for which signature validation failed")
            .withUnit("events");
    private final LongAccumulator sigValidationFailedAccumulator;

    // --- Deduplication metrics ---
    private static final LongAccumulator.Config DISPARATE_SIGNATURE_CONFIG = new LongAccumulator.Config(
                    PLATFORM_CATEGORY, "eventsWithDisparateSignature")
            .withDescription(
                    "Events received that match a descriptor of a previous event, but with a different signature")
            .withUnit("events");
    private final LongAccumulator disparateSignatureAccumulator;

    private final CountPerSecond duplicateEventsPerSecond;

    private static final RunningAverageMetric.Config AVG_DUPLICATE_PERCENT_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "dupEvPercent")
            .withDescription("percentage of events received that are already known")
            .withFormat(FORMAT_10_2);
    private final RunningAverageMetric avgDuplicatePercent;

    /** Stage name for pipeline tracking after hashing. */
    static final String STAGE_HASHING = "hashing";
    /** Stage name for pipeline tracking after field validation. */
    static final String STAGE_VALIDATION = "validation";
    /** Stage name for pipeline tracking after deduplication. */
    static final String STAGE_DEDUPLICATION = "deduplication";
    /** Stage name for pipeline tracking after signature verification. */
    static final String STAGE_VERIFICATION = "verification";

    /**
     * Constructor.
     *
     * @param metrics            the metrics system
     * @param time               the time source
     * @param eventHasher        hashes events
     * @param eventFieldValidator validates event fields
     * @param verifierFactory    creates a {@link BytesSignatureVerifier} for a given public key
     * @param rosterHistory      the complete roster history
     * @param intakeEventCounter tracks event counts in the intake pipeline
     * @param pipelineTracker    optional tracker for per-stage event delay metrics
     */
    public DefaultEventIntakeProcessor(
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final EventHasher eventHasher,
            @NonNull final EventFieldValidator eventFieldValidator,
            @NonNull final Function<PublicKey, BytesSignatureVerifier> verifierFactory,
            @NonNull final RosterHistory rosterHistory,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @Nullable final EventPipelineTracker pipelineTracker) {

        this.eventHasher = Objects.requireNonNull(eventHasher);
        this.eventFieldValidator = Objects.requireNonNull(eventFieldValidator);
        this.verifierFactory = Objects.requireNonNull(verifierFactory);
        this.rosterHistory = Objects.requireNonNull(rosterHistory);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
        this.pipelineTracker = pipelineTracker;

        this.rateLimitedLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);

        // Signature verification metrics
        this.sigValidationFailedAccumulator = metrics.getOrCreate(SIG_VALIDATION_FAILED_CONFIG);

        // Deduplication metrics
        this.disparateSignatureAccumulator = metrics.getOrCreate(DISPARATE_SIGNATURE_CONFIG);
        this.duplicateEventsPerSecond = new CountPerSecond(
                metrics,
                new CountPerSecond.Config(PLATFORM_CATEGORY, "dupEv_per_sec")
                        .withDescription("number of events received per second that are already known")
                        .withUnit("hz"));
        this.avgDuplicatePercent = metrics.getOrCreate(AVG_DUPLICATE_PERCENT_CONFIG);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformEvent processUnhashedEvent(@NonNull final PlatformEvent event) {
        eventHasher.hashEvent(event);
        recordStage(STAGE_HASHING, event);
        return processHashedEvent(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformEvent processHashedEvent(@NonNull final PlatformEvent event) {
        // 1. Ancient check — once, before any work
        if (eventWindow.isAncient(event)) {
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
            return null;
        }

        // 2. Validate event fields (null checks, byte lengths, transaction limits, etc.)
        if (!eventFieldValidator.isValid(event)) {
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
            return null;
        }
        recordStage(STAGE_VALIDATION, event);

        // 3. Deduplicate by (descriptor, signature) pair
        if (!deduplicate(event)) {
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
            return null;
        }
        recordStage(STAGE_DEDUPLICATION, event);

        // 4. Verify signature (RUNTIME events are trusted — we just created and signed them)
        if (event.getOrigin() != EventOrigin.RUNTIME) {
            if (!isSignatureValid(event)) {
                intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
                sigValidationFailedAccumulator.update(1);
                return null;
            }
        }
        recordStage(STAGE_VERIFICATION, event);

        return event;
    }

    /**
     * Record the pipeline delay for the given event at the named stage.
     * No-op when pipeline tracking is disabled.
     */
    private void recordStage(@NonNull final String stage, @NonNull final PlatformEvent event) {
        if (pipelineTracker != null) {
            pipelineTracker.recordEvent(stage, event);
        }
    }

    // =========================================================================
    // Deduplication
    // =========================================================================

    /**
     * Check if the event has been seen before. An event is considered a duplicate if both its
     * descriptor and signature have already been observed.
     *
     * @param event the event to check
     * @return true if the event is new (not a duplicate), false if it is a duplicate
     */
    private boolean deduplicate(@NonNull final PlatformEvent event) {
        // ConcurrentHashMap.computeIfAbsent is atomic — only one thread creates the set
        final Set<Bytes> signatures =
                observedEvents.computeIfAbsent(event.getDescriptor(), k -> ConcurrentHashMap.newKeySet());

        // ConcurrentHashMap.KeySetView.add is thread-safe
        if (signatures.add(event.getSignature())) {
            if (signatures.size() > 1) {
                // Same descriptor, different signature — possible malicious node
                disparateSignatureAccumulator.update(1);
            }
            avgDuplicatePercent.update(0);
            return true;
        } else {
            // Exact duplicate (same descriptor and signature)
            duplicateEventsPerSecond.count(1);
            avgDuplicatePercent.update(100);
            return false;
        }
    }

    // =========================================================================
    // Signature verification (with per-roster, per-node verifier caching)
    // =========================================================================

    /**
     * Determine whether a given event has a valid signature.
     *
     * @param event the event to validate
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

        final boolean isValid = verifier.verify(event.getHash().getBytes(), event.getSignature());

        if (!isValid) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Event failed signature check. Event: {}, Signature: {}, Hash: {}",
                    event,
                    event.getSignature().toHex(),
                    event.getHash());
        }

        return isValid;
    }

    /**
     * Look up the cached signature verifier for a given node in the given roster.
     *
     * @param roster the roster to look up the node in
     * @param nodeId the node ID to look up
     * @return the cached verifier, or null if the public key could not be resolved
     */
    @Nullable
    private BytesSignatureVerifier lookupVerifier(@NonNull final Roster roster, @NonNull final NodeId nodeId) {
        final ConcurrentMap<NodeId, BytesSignatureVerifier> rosterCache =
                verifierCache.computeIfAbsent(roster, k -> new ConcurrentHashMap<>());

        final BytesSignatureVerifier verifier = rosterCache.computeIfAbsent(nodeId, k -> {
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
            rateLimitedLogger.error(EXCEPTION.getMarker(), "Node {} doesn't exist in applicable roster", nodeId);
            return null;
        }

        final X509Certificate cert = RosterUtils.fetchGossipCaCertificate(rosterEntry);
        final PublicKey publicKey = cert == null ? null : cert.getPublicKey();

        if (publicKey == null) {
            rateLimitedLogger.error(EXCEPTION.getMarker(), "Cannot find publicKey for creator with ID: {}", nodeId);
            return null;
        }

        return verifierFactory.apply(publicKey);
    }

    // =========================================================================
    // INJECT wire handlers
    // =========================================================================

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = Objects.requireNonNull(eventWindow);
        // Purge ancient entries from the deduplication map.
        // ConcurrentHashMap.entrySet().removeIf is safe during concurrent reads/writes.
        observedEvents.entrySet().removeIf(e -> e.getKey().birthRound() < eventWindow.ancientThreshold());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateRosterHistory(@NonNull final RosterHistory rosterHistory) {
        this.rosterHistory = Objects.requireNonNull(rosterHistory);
        // Replace with a new map — concurrent readers still hold a reference to the old map
        // and can safely finish their lookups.
        this.verifierCache = new ConcurrentHashMap<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        observedEvents.clear();
    }
}
