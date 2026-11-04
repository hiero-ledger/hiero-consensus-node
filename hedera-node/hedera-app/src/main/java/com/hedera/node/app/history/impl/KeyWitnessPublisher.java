// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Publishes locally observed signing witnesses until consensus state confirms their durable acceptance.
 * State is read only on reconciliation. Signature callbacks work exclusively with immutable snapshots,
 * including when their captured signing generation has subsequently retired.
 */
final class KeyWitnessPublisher {
    private static final Logger log = LogManager.getLogger(KeyWitnessPublisher.class);
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(30);
    static final Duration MAX_RETRY_INTERVAL = Duration.ofMinutes(5);
    static final Duration STATE_REFRESH_INTERVAL = Duration.ofSeconds(30);

    private final Function<HintsKeyWitness, CompletableFuture<Void>> submitter;
    private final BiConsumer<Integer, Long> updateMetrics;
    private final Map<Long, Candidate> candidates = new HashMap<>();
    private Map<Long, HintsKeyWitnessRecord> pending = Map.of();
    private Instant consensusNow = Instant.EPOCH;
    private Instant nextStateRefresh = Instant.MIN;
    private Instant nextSubmission = Instant.MAX;

    @Nullable
    private Instant oldestKnownActivation;

    private boolean active;
    private long observedActiveConstructionId = -1;

    KeyWitnessPublisher(@NonNull final Function<HintsKeyWitness, CompletableFuture<Void>> submitter) {
        this(submitter, (count, age) -> {});
    }

    KeyWitnessPublisher(
            @NonNull final Function<HintsKeyWitness, CompletableFuture<Void>> submitter,
            @NonNull final BiConsumer<Integer, Long> updateMetrics) {
        this.submitter = requireNonNull(submitter);
        this.updateMetrics = requireNonNull(updateMetrics);
    }

    /** Refreshes pending adopted generations from consensus state and retries any retained candidates. */
    synchronized void reconcile(
            @NonNull final ReadableHistoryStore store,
            @NonNull final Instant now,
            final boolean isActive,
            final long activeConstructionId) {
        requireNonNull(store);
        requireNonNull(now);
        final boolean clockMovedBack = now.isBefore(consensusNow);
        if (observedActiveConstructionId != activeConstructionId || clockMovedBack || !now.isBefore(nextStateRefresh)) {
            refreshPending(store, now, activeConstructionId, clockMovedBack);
        }
        consensusNow = now;
        active = isActive;
        updateMetrics.accept(
                pending.size(),
                oldestKnownActivation == null
                        ? 0L
                        : Math.max(
                                0, Duration.between(oldestKnownActivation, now).toSeconds()));
        if (active && !now.isBefore(nextSubmission)) {
            candidates.forEach(this::submitIfDue);
            refreshNextSubmission();
        }
    }

    private void refreshPending(
            final ReadableHistoryStore store,
            final Instant now,
            final long activeConstructionId,
            final boolean clockMovedBack) {
        final var nextPending = new HashMap<Long, HintsKeyWitnessRecord>();
        if (observedActiveConstructionId != activeConstructionId || clockMovedBack) {
            // Historical adoption records are append-only. Scan them at startup and at a generation change,
            // then read only unresolved records in steady state instead of traversing years of history each round.
            store.keyProvenanceWithWitnesses().forEachRemaining(record -> addIfPending(nextPending, record));
        } else {
            pending.keySet().forEach(id -> addIfPending(nextPending, store.getKeyWitnessRecord(id)));
        }
        if (!nextPending.keySet().equals(pending.keySet())) {
            log.info("{} adopted hinTS generations await a durable block-signing witness", nextPending.size());
        }
        candidates
                .entrySet()
                .removeIf(entry -> !nextPending.containsKey(entry.getKey())
                        || !nextPending.get(entry.getKey()).equals(pending.get(entry.getKey())));
        pending = Map.copyOf(nextPending);
        observedActiveConstructionId = activeConstructionId;
        nextStateRefresh = now.plus(STATE_REFRESH_INTERVAL);
        oldestKnownActivation = pending.values().stream()
                .filter(HintsKeyWitnessRecord::hasActivationTime)
                .map(record -> asInstant(record.activationTimeOrThrow()))
                .min(Instant::compareTo)
                .orElse(null);
        if (clockMovedBack) {
            candidates.values().forEach(candidate -> {
                candidate.lastSubmission = null;
                candidate.retryInterval = RETRY_INTERVAL;
            });
        }
        refreshNextSubmission();
    }

    /** Offers a directly signed block, retaining at most one candidate for each pending adopted generation. */
    synchronized void offer(@NonNull final Bytes verificationKey, @NonNull final HintsKeyWitness witness) {
        requireNonNull(verificationKey);
        requireNonNull(witness);
        final long constructionId = witness.targetHintsConstructionId();
        final var record = pending.get(constructionId);
        if (constructionId <= 0
                || record == null
                || !record.provenanceOrThrow().targetVerificationKey().equals(verificationKey)
                || !HintsKeyWitnessVerifier.hasValidStructure(witness)
                || (record.hasActivationTime()
                        && !asInstant(witness.timestampOrThrow()).isAfter(asInstant(record.activationTimeOrThrow())))) {
            return;
        }
        final var candidate = candidates.computeIfAbsent(constructionId, ignored -> new Candidate(witness));
        // A later block can replace a candidate rejected at admission, without resetting the retry throttle.
        // Retired generations keep their last candidate until consensus confirms a durable witness.
        if (asInstant(witness.timestampOrThrow()).isAfter(asInstant(candidate.witness.timestampOrThrow()))) {
            candidate.witness = witness;
        }
        submitIfDue(constructionId, candidate);
        refreshNextSubmission();
    }

    synchronized void stop() {
        active = false;
        observedActiveConstructionId = -1;
        nextStateRefresh = Instant.MIN;
        nextSubmission = Instant.MAX;
        oldestKnownActivation = null;
        pending = Map.of();
        candidates.clear();
        updateMetrics.accept(0, 0L);
    }

    private static void addIfPending(
            final Map<Long, HintsKeyWitnessRecord> records, @Nullable final HintsKeyWitnessRecord record) {
        if (record != null && record.hasProvenance() && !record.hasWitness()) {
            records.put(record.provenanceOrThrow().targetHintsConstructionId(), record);
        }
    }

    private void submitIfDue(final long constructionId, @NonNull final Candidate candidate) {
        if (!active
                || candidate.inFlight
                || (candidate.lastSubmission != null
                        && consensusNow.isBefore(candidate.lastSubmission.plus(candidate.retryInterval)))) {
            return;
        }
        if (candidate.lastSubmission != null) {
            candidate.retryInterval = Duration.ofSeconds(
                    Math.min(MAX_RETRY_INTERVAL.toSeconds(), candidate.retryInterval.toSeconds() * 2));
        }
        candidate.lastSubmission = consensusNow;
        candidate.inFlight = true;
        try {
            requireNonNull(submitter.apply(candidate.witness)).whenComplete((ignored, failure) -> {
                onSubmissionComplete(constructionId, candidate);
                if (failure != null) {
                    log.warn(
                            "Failed to submit block-signing witness for hinTS construction #{}",
                            constructionId,
                            failure);
                }
            });
        } catch (RuntimeException e) {
            onSubmissionComplete(constructionId, candidate);
            log.warn("Failed to schedule block-signing witness for hinTS construction #{}", constructionId, e);
        }
    }

    private synchronized void onSubmissionComplete(final long constructionId, @NonNull final Candidate candidate) {
        if (candidates.get(constructionId) == candidate) {
            candidate.inFlight = false;
            refreshNextSubmission();
        }
    }

    private void refreshNextSubmission() {
        nextSubmission = candidates.values().stream()
                .filter(candidate -> !candidate.inFlight)
                .map(candidate -> candidate.lastSubmission == null
                        ? Instant.MIN
                        : candidate.lastSubmission.plus(candidate.retryInterval))
                .min(Instant::compareTo)
                .orElse(Instant.MAX);
    }

    private static final class Candidate {
        private HintsKeyWitness witness;

        @Nullable
        private Instant lastSubmission;

        private Duration retryInterval = RETRY_INTERVAL;
        private boolean inFlight;

        private Candidate(final HintsKeyWitness witness) {
            this.witness = witness;
        }
    }
}
