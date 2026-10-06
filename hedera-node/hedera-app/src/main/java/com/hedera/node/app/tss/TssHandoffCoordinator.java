// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Coordinates handoffs between the hinTS and history constructions.
 */
public final class TssHandoffCoordinator {
    private static final Logger log = LogManager.getLogger(TssHandoffCoordinator.class);

    private TssHandoffCoordinator() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Whether force handoffs should use a joint hinTS/history promotion.
     *
     * @param tssConfig the TSS configuration
     * @return whether to use the joint forced handoff path
     */
    public static boolean usesJointForcedHandoff(@NonNull final TssConfig tssConfig) {
        requireNonNull(tssConfig);
        return tssConfig.hintsEnabled() && tssConfig.historyEnabled() && tssConfig.forceHandoffs();
    }

    /**
     * Hands off only if both the next hinTS and history constructions can be promoted together.
     * In non-WRAPS mode, the history construction may be force-promoted across a roster-hash mismatch,
     * but only if it has the matching aggregate Schnorr witness.
     *
     * @param historyStore the writable history store
     * @param hintsStore the writable hinTS store
     * @param historyService the history service
     * @param hintsService the hinTS service
     * @param previousRoster the previous roster
     * @param adoptedRoster the adopted roster
     * @param adoptedRosterHash the adopted roster hash
     * @param tssConfig the TSS configuration
     * @return whether both handoffs happened
     */
    public static boolean tryForcedJointHandoff(
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final HistoryService historyService,
            @NonNull final HintsService hintsService,
            @NonNull final Roster previousRoster,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(tssConfig);
        if (!tssConfig.wrapsEnabled()) {
            return tryForcedNonWrapsJointHandoff(
                    historyStore,
                    hintsStore,
                    historyService,
                    hintsService,
                    previousRoster,
                    adoptedRoster,
                    adoptedRosterHash);
        }
        final var proof = matchingCompletedHistoryProof(historyStore, hintsStore, tssConfig);
        if (proof.isEmpty()) {
            return false;
        }
        return promoteTogether(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                previousRoster,
                adoptedRoster,
                adoptedRosterHash,
                proof.get(),
                false);
    }

    /**
     * Forces a non-WRAPS handoff only if both the next hinTS and history constructions can be promoted
     * together, and the test-only aggregate Schnorr witness proves the same hinTS verification key that
     * the signer is about to adopt.
     *
     * @param historyStore the writable history store
     * @param hintsStore the writable hinTS store
     * @param historyService the history service
     * @param hintsService the hinTS service
     * @param previousRoster the previous roster
     * @param adoptedRoster the adopted roster
     * @param adoptedRosterHash the adopted roster hash
     * @return whether both handoffs happened
     */
    public static boolean tryForcedNonWrapsJointHandoff(
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final HistoryService historyService,
            @NonNull final HintsService hintsService,
            @NonNull final Roster previousRoster,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash) {
        requireNonNull(historyStore);
        requireNonNull(hintsStore);
        requireNonNull(historyService);
        requireNonNull(hintsService);
        requireNonNull(previousRoster);
        requireNonNull(adoptedRoster);
        requireNonNull(adoptedRosterHash);
        final var proof = matchingAggregateHistoryProof(historyStore, hintsStore);
        if (proof.isEmpty()) {
            return false;
        }
        return promoteTogether(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                previousRoster,
                adoptedRoster,
                adoptedRosterHash,
                proof.get(),
                true);
    }

    private static boolean promoteTogether(
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final HistoryService historyService,
            @NonNull final HintsService hintsService,
            @NonNull final Roster previousRoster,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash,
            @NonNull final HistoryProof proof,
            final boolean forceHistoryHandoff) {
        if (!historyStore.handoff(previousRoster, adoptedRoster, adoptedRosterHash, forceHistoryHandoff)) {
            log.warn("Skipping forced TSS handoff because history construction did not promote");
            return false;
        }
        if (!hintsService.handoff(hintsStore, previousRoster, adoptedRoster, adoptedRosterHash, true)) {
            log.error("History construction promoted without hinTS construction during guarded forced handoff");
            return false;
        }
        historyService.setLatestHistoryProof(proof);
        return true;
    }

    private static @NonNull Optional<HistoryProof> matchingCompletedHistoryProof(
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final TssConfig tssConfig) {
        final var hintsConstruction = hintsStore.getNextConstruction();
        final var historyConstruction = historyStore.getNextConstruction();
        if (!hintsConstruction.hasHintsScheme()) {
            log.warn(
                    "Skipping forced TSS handoff because next hinTS construction #{} is incomplete",
                    hintsConstruction.constructionId());
            return Optional.empty();
        }
        if (!HistoryService.isCompleted(historyConstruction, tssConfig)) {
            log.warn(
                    "Skipping forced TSS handoff because next history construction #{} is incomplete",
                    historyConstruction.constructionId());
            return Optional.empty();
        }
        final var verificationKey =
                hintsConstruction.hintsSchemeOrThrow().preprocessedKeysOrThrow().verificationKey();
        final var historyProof = historyConstruction.targetProofOrThrow();
        if (!historyProof.hasTargetHistory()) {
            log.warn(
                    "Skipping forced TSS handoff because history construction #{} has no target history",
                    historyConstruction.constructionId());
            return Optional.empty();
        }
        if (!historyProof.hasChainOfTrustProof()) {
            log.warn(
                    "Skipping forced TSS handoff because history construction #{} has no chain-of-trust proof",
                    historyConstruction.constructionId());
            return Optional.empty();
        }
        final var targetMetadata = historyProof.targetHistoryOrThrow().metadata();
        if (!targetMetadata.equals(verificationKey)) {
            log.warn(
                    "Skipping forced TSS handoff because history construction #{} proves metadata that does not "
                            + "match hinTS construction #{}",
                    historyConstruction.constructionId(),
                    hintsConstruction.constructionId());
            return Optional.empty();
        }
        return Optional.of(historyProof);
    }

    private static @NonNull Optional<HistoryProof> matchingAggregateHistoryProof(
            @NonNull final WritableHistoryStore historyStore, @NonNull final WritableHintsStore hintsStore) {
        final var hintsConstruction = hintsStore.getNextConstruction();
        final var historyConstruction = historyStore.getNextConstruction();
        if (!hintsConstruction.hasHintsScheme()) {
            log.warn(
                    "Skipping forced TSS handoff because next hinTS construction #{} is incomplete",
                    hintsConstruction.constructionId());
            return Optional.empty();
        }
        if (!historyConstruction.hasTargetProof()) {
            log.warn(
                    "Skipping forced TSS handoff because next history construction #{} is incomplete",
                    historyConstruction.constructionId());
            return Optional.empty();
        }
        final var verificationKey =
                hintsConstruction.hintsSchemeOrThrow().preprocessedKeysOrThrow().verificationKey();
        final var historyProof = historyConstruction.targetProofOrThrow();
        if (!historyProof.hasTargetHistory()) {
            log.warn(
                    "Skipping forced TSS handoff because history construction #{} has no target history",
                    historyConstruction.constructionId());
            return Optional.empty();
        }
        if (!historyProof.hasChainOfTrustProof()) {
            log.warn(
                    "Skipping forced TSS handoff because history construction #{} has no chain-of-trust proof",
                    historyConstruction.constructionId());
            return Optional.empty();
        }
        final var chainOfTrustProof = historyProof.chainOfTrustProofOrThrow();
        if (!chainOfTrustProof.hasAggregatedNodeSignatures()) {
            log.warn(
                    "Skipping forced TSS handoff because history construction #{} has no aggregate Schnorr witness",
                    historyConstruction.constructionId());
            return Optional.empty();
        }
        final var aggregate = chainOfTrustProof.aggregatedNodeSignaturesOrThrow();
        final var targetMetadata = historyProof.targetHistoryOrThrow().metadata();
        if (!targetMetadata.equals(verificationKey)
                || !aggregate.verificationKey().equals(verificationKey)) {
            log.warn(
                    "Skipping forced TSS handoff because history construction #{} proves metadata that does not "
                            + "match hinTS construction #{}",
                    historyConstruction.constructionId(),
                    hintsConstruction.constructionId());
            return Optional.empty();
        }
        return Optional.of(historyProof);
    }
}
