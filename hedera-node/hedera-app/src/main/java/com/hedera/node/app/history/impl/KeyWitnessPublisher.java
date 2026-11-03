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

    private final Function<HintsKeyWitness, CompletableFuture<Void>> submitter;
    private final Map<Long, Candidate> candidates = new HashMap<>();
    private Map<Long, HintsKeyWitnessRecord> pending = Map.of();
    private Instant consensusNow = Instant.EPOCH;
    private boolean active;
    private long observedActiveConstructionId = -1;

    KeyWitnessPublisher(@NonNull final Function<HintsKeyWitness, CompletableFuture<Void>> submitter) {
        this.submitter = requireNonNull(submitter);
    }

    /** Refreshes pending adopted generations from consensus state and retries any retained candidates. */
    synchronized void reconcile(
            @NonNull final ReadableHistoryStore store,
            @NonNull final Instant now,
            final boolean isActive,
            final long activeConstructionId) {
        requireNonNull(store);
        requireNonNull(now);
        final var nextPending = new HashMap<Long, HintsKeyWitnessRecord>();
        if (observedActiveConstructionId != activeConstructionId || now.isBefore(consensusNow)) {
            // Historical adoption records are append-only. Scan them at startup and at a generation change,
            // then read only unresolved records in steady state instead of traversing years of history each round.
            store.keyProvenanceWithWitnesses().forEachRemaining(record -> addIfPending(nextPending, record));
            observedActiveConstructionId = activeConstructionId;
            if (now.isBefore(consensusNow)) {
                candidates.values().forEach(candidate -> candidate.lastSubmission = null);
            }
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
        consensusNow = now;
        active = isActive;
        candidates.forEach(this::submitIfDue);
    }

    /** Offers a directly signed block, retaining at most one candidate for each pending adopted generation. */
    synchronized void offer(@NonNull final Bytes verificationKey, @NonNull final HintsKeyWitness witness) {
        requireNonNull(verificationKey);
        requireNonNull(witness);
        final long constructionId = witness.targetHintsConstructionId();
        final var record = pending.get(constructionId);
        if (record == null
                || !record.provenanceOrThrow().targetVerificationKey().equals(verificationKey)
                || !HintsKeyWitnessVerifier.hasValidStructure(witness)
                || (record.hasActivationTime()
                        && !asInstant(witness.timestampOrThrow()).isAfter(asInstant(record.activationTimeOrThrow())))) {
            return;
        }
        final var candidate = candidates.computeIfAbsent(constructionId, ignored -> new Candidate(witness));
        // A valid later block replaces a candidate that may have failed admission. Do not retry one
        // rejected candidate forever, and do not retain an unbounded history of locally signed blocks.
        if (asInstant(witness.timestampOrThrow()).isAfter(asInstant(candidate.witness.timestampOrThrow()))) {
            candidate.witness = witness;
        }
        submitIfDue(constructionId, candidate);
    }

    synchronized void stop() {
        active = false;
        observedActiveConstructionId = -1;
        pending = Map.of();
        candidates.clear();
    }

    private static void addIfPending(
            final Map<Long, HintsKeyWitnessRecord> records, @Nullable final HintsKeyWitnessRecord record) {
        if (record != null && record.hasProvenance() && !record.hasWitness()) {
            records.put(record.provenanceOrThrow().targetHintsConstructionId(), record);
        }
    }

    private void submitIfDue(final long constructionId, @NonNull final Candidate candidate) {
        if (!active
                || (candidate.lastSubmission != null
                        && consensusNow.isBefore(candidate.lastSubmission.plus(RETRY_INTERVAL)))) {
            return;
        }
        candidate.lastSubmission = consensusNow;
        try {
            requireNonNull(submitter.apply(candidate.witness)).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    log.warn(
                            "Failed to submit block-signing witness for hinTS construction #{}",
                            constructionId,
                            failure);
                    retryOnNextOpportunity(constructionId, candidate);
                }
            });
        } catch (RuntimeException e) {
            candidate.lastSubmission = null;
            log.warn("Failed to schedule block-signing witness for hinTS construction #{}", constructionId, e);
        }
    }

    private synchronized void retryOnNextOpportunity(final long constructionId, @NonNull final Candidate candidate) {
        if (candidates.get(constructionId) == candidate) {
            candidate.lastSubmission = null;
        }
    }

    private static final class Candidate {
        private HintsKeyWitness witness;

        @Nullable
        private Instant lastSubmission;

        private Candidate(final HintsKeyWitness witness) {
            this.witness = witness;
        }
    }
}
