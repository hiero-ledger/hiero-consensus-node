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
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * Implementation of {@link EventIntakeProcessor}. Combines hashing, field validation,
 * deduplication, and signature verification into a single concurrent component.
 *
 * <p>All shared state is accessed through thread-safe structures ({@link ConcurrentHashMap},
 * {@code volatile} fields), making this component safe for use with a {@code CONCURRENT}
 * task scheduler.
 */
public class ConcurrentEventIntakeProcessor implements EventIntakeProcessor {
    private static final Logger logger = LogManager.getLogger(ConcurrentEventIntakeProcessor.class);

    private static final Duration MINIMUM_LOG_PERIOD = Duration.ofMinutes(1);

    private final EventHasher eventHasher;

    private final EventFieldValidator eventFieldValidator;

    /**
     * Deduplication map bucketed by birth round.
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<EventDescriptorWrapper, Set<Bytes>>> observedEvents =
            new ConcurrentHashMap<>();

    private final Function<PublicKey, BytesSignatureVerifier> verifierFactory;
    private volatile RosterHistory rosterHistory;

    /**
     * Shared public key cache keyed by {@code (nodeId, birthRound)}.
     * Ancient entries are evicted in {@link #setEventWindow}.
     */
    private final ConcurrentHashMap<VerifierKey, PublicKey> publicKeyCache = new ConcurrentHashMap<>();

    /**
     * Per-thread verifier cache keyed by {@link PublicKey}.
     * This resolves the situation where we don't know if BytesSignatureVerifier implementation is concurrent or not
     * with minimal overhead.
     * This cache is never cleaned up but memory is bounded by nodes × roster_changes × threads and acceptable (before DAB)
     */
    private final ThreadLocal<HashMap<PublicKey, BytesSignatureVerifier>> threadLocalVerifiers =
            ThreadLocal.withInitial(HashMap::new);

    private record VerifierKey(NodeId nodeId, long birthRound) {}

    private volatile EventWindow eventWindow = EventWindow.getGenesisEventWindow();
    private final IntakeEventCounter intakeEventCounter;

    @Nullable
    private final EventPipelineTracker pipelineTracker;

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
    public ConcurrentEventIntakeProcessor(
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

        // 2. Validate event fields
        if (!eventFieldValidator.isValid(event)) {
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
            recordStage(STAGE_VALIDATION, event);
            return null;
        }
        recordStage(STAGE_VALIDATION, event);

        // 3. Deduplicate by (descriptor, signature) pair
        if (duplicate(event)) {
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
            recordStage(STAGE_DEDUPLICATION, event);
            return null;
        }
        recordStage(STAGE_DEDUPLICATION, event);

        // 4. Verify signature (RUNTIME events are trusted — we just created and signed them)
        if (event.getOrigin() != EventOrigin.RUNTIME) {
            if (!isSignatureValid(event)) {
                intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
                sigValidationFailedAccumulator.update(1);
                rateLimitedLogger.error(
                        EXCEPTION.getMarker(),
                        "Event failed signature check. Event: {}, Signature: {}, Hash: {}",
                        event,
                        event.getSignature().toHex(),
                        event.getHash());
                recordStage(STAGE_VERIFICATION, event);
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

    /**
     * Checks for duplicates events.
     * <p>
     * A duplicate event is defined as an event with an identical descriptor and identical signature to an event that has
     * already been observed.
     * <p>
     * It is necessary to consider the signature bytes when determining if an event is a duplicate, not just the descriptor
     * or hash. This guards against a malicious node gossiping the same event with different signatures, or a node gossiping
     * another node's event with a modified signature. If we went only off the descriptor or hash, we might discard the
     * correct version of an event as a duplicate, because a malicious version has already been received. Instead, the
     * deduplication lets all versions of the event through that have a unique descriptor/signature pair, and the signature
     * validator later will handle discarding bad versions.
     *
     *
     * @param event the event to check
     * @return true if the event is a duplicate from a previous observed event
     */
    private boolean duplicate(@NonNull final PlatformEvent event) {
        // Two-level lookup: birth round → (descriptor → signatures).
        // computeIfAbsent on both levels is atomic — only one thread creates each bucket/set.
        final ConcurrentHashMap<EventDescriptorWrapper, Set<Bytes>> bucket =
                observedEvents.computeIfAbsent(event.getBirthRound(), k -> new ConcurrentHashMap<>());
        final Set<Bytes> signatures = bucket.computeIfAbsent(event.getDescriptor(), k -> ConcurrentHashMap.newKeySet());
        // thread-safe
        if (signatures.add(
                event.getSignature())) { // Either exact duplicate (same descriptor and signature) or different
            // signature duplicate
            if (signatures.size() > 1) { // Same descriptor, different signature — possible malicious node

                //// spotless:off
                // Exists a scheduling scenario in the first insertion on the set,
                // where there is 1 more thread updating disparateSignatureAccumulator than it should,
                // but its is probably ok, the important thing is that only one add at the time:
                // Thread A                                   |Thread B
                // signatures = bucket ...
                //                                            |signatures = bucket ...
                // signatures.add(event.getSignature())->true
                //                                            |signatures.add(event.getSignature())->true
                // if(signatures.size() > 1)-> true (should be false)
                //                                            |if(signatures.size() > 1)-> true
                //// spotless:on

                disparateSignatureAccumulator.update(1);
            }
            avgDuplicatePercent.update(0);
            return false;
        } else {
            duplicateEventsPerSecond.count(1);
            avgDuplicatePercent.update(100);
            return true;
        }
    }

    /**
     * Determine whether a given event has a valid signature.
     *
     * @param event the event to validate
     * @return true if the event has a valid signature, otherwise false
     */
    private boolean isSignatureValid(@NonNull final PlatformEvent event) {
        // 1. Resolve the public key from the shared cache (expensive roster + cert parsing once globally)
        final VerifierKey key = new VerifierKey(event.getCreatorId(), event.getBirthRound());
        final PublicKey publicKey = publicKeyCache.computeIfAbsent(key, this::resolvePublicKey);
        // it does not cache null returns so we will call
        // resolvePublicKey multiple times for every invalid: nodeId-birthround combination
        if (publicKey == null) {
            return false;
        }

        // 2. Get or create a per-thread verifier for this public key (no contention).
        //    Before dynamic address book, this stores at most 2 × nodes verifiers per thread.
        final BytesSignatureVerifier verifier = threadLocalVerifiers.get().computeIfAbsent(publicKey, verifierFactory);

        return verifier.verify(event.getHash().getBytes(), event.getSignature());
    }

    /**
     * Resolve the public key for a given node from the roster.
     *
     * @param key the node ID and birth round for look-ups
     * @return the node's public key, or null if it could not be resolved
     */
    @Nullable
    private PublicKey resolvePublicKey(@NonNull final VerifierKey key) {
        final Roster roster = rosterHistory.getRosterForRound(key.birthRound());
        if (roster == null) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Cannot validate events for birth round {} without a roster",
                    key.birthRound());
            return null;
        }
        final RosterEntry rosterEntry;
        try {
            rosterEntry = RosterUtils.getRosterEntry(roster, key.nodeId().id());
        } catch (RosterEntryNotFoundException e) {
            rateLimitedLogger.error(EXCEPTION.getMarker(), "Node {} doesn't exist in applicable roster", key.nodeId());
            return null;
        }

        final X509Certificate cert = RosterUtils.fetchGossipCaCertificate(rosterEntry);
        if (cert == null || cert.getPublicKey() == null) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(), "Cannot find publicKey for creator with ID: {}", key.nodeId());
            return null;
        }

        return cert.getPublicKey();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = Objects.requireNonNull(eventWindow);
        // Purge all birth-round buckets below the ancient threshold.
        // Iterates only round keys (~20), not every event entry.
        observedEvents.keySet().removeIf(round -> round < eventWindow.ancientThreshold());
        // Evict ancient public key cache entries. Bounded by nodes × active rounds.
        publicKeyCache.keySet().removeIf(key -> key.birthRound() < eventWindow.ancientThreshold());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateRosterHistory(@NonNull final RosterHistory rosterHistory) {
        this.rosterHistory = Objects.requireNonNull(rosterHistory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        observedEvents.clear();
        publicKeyCache.clear();
    }
}
