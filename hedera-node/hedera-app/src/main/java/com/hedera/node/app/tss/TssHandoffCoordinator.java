// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static com.hedera.node.app.history.impl.ProofControllers.isWrapsExtensible;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.roster.RosterUtils;

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
    public static boolean tryJointHandoff(
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
        if (!isReadyForHandoff(hintsStore, historyStore, adoptedRosterHash, true)
                || hintsStore.getNextConstruction().numParties() < HintsService.partySizeForRoster(adoptedRoster)) {
            return false;
        }
        final var proof = matchingCompletedHistoryProof(historyStore, hintsStore);
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
                proof.get());
    }

    /** Checks all cryptographic dependencies before any state is promoted. */
    public static boolean isReadyForHandoff(
            @NonNull final ReadableHintsStore hintsStore,
            @NonNull final ReadableHistoryStore historyStore,
            @NonNull final Bytes targetRosterHash,
            final boolean historyEnabled) {
        requireNonNull(hintsStore);
        requireNonNull(historyStore);
        requireNonNull(targetRosterHash);
        return hintsStore.isReadyToAdopt(targetRosterHash)
                && hintsStore.getNextConstruction().numParties()
                        >= hintsStore.getCrsState().numParties()
                && (!historyEnabled
                        || (historyStore.isReadyToAdopt(targetRosterHash)
                                && matchingCompletedHistoryProof(historyStore, hintsStore)
                                        .isPresent()));
    }

    /**
     * Whether a transport-only override, or an explicitly imported completed construction, can
     * retain the active signing generation. Membership or weight changes require prepared state.
     */
    public static boolean canRetainActiveConstruction(
            @NonNull final ReadableHintsStore hintsStore,
            @NonNull final ReadableHistoryStore historyStore,
            @NonNull final Roster previousRoster,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash,
            final boolean historyEnabled) {
        return canRetainActiveConstruction(
                hintsStore, historyStore, previousRoster, adoptedRoster, adoptedRosterHash, historyEnabled, null);
    }

    /**
     * Also accepts a previously prepared roster whose transport details were changed by an override.
     * The supplied roster must hash to the active construction's target; a matching subset of signing
     * parties is not sufficient evidence of the target's weighted membership.
     */
    public static boolean canRetainActiveConstruction(
            @NonNull final ReadableHintsStore hintsStore,
            @NonNull final ReadableHistoryStore historyStore,
            @NonNull final Roster previousRoster,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash,
            final boolean historyEnabled,
            @Nullable final Roster preparedRoster) {
        requireNonNull(hintsStore);
        requireNonNull(historyStore);
        requireNonNull(previousRoster);
        requireNonNull(adoptedRoster);
        requireNonNull(adoptedRosterHash);
        final var active = hintsStore.getActiveConstruction();
        if (!active.hasHintsScheme() || !active.hintsSchemeOrThrow().hasPreprocessedKeys()) {
            return false;
        }
        final var crs = hintsStore.getCrsState();
        if (crs.stage() != CRSStage.COMPLETED
                || active.crsId() <= 0
                || active.crsId() != crs.ceremonyId()
                || active.numParties() != crs.numParties()
                || active.numParties() < HintsService.partySizeForRoster(adoptedRoster)) {
            return false;
        }
        try {
            if (HintsLibrary.crsPartySizeFrom(crs.crs()) != active.numParties()) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        // A weight rotation can promote signing state before the platform adopts its candidate.
        // Therefore the previous platform roster is evidence only when it was this scheme's target.
        if (preparedRosterHashForAdoption(active.targetRosterHash(), adoptedRoster, adoptedRosterHash, previousRoster)
                        .isEmpty()
                && preparedRosterHashForAdoption(
                                active.targetRosterHash(), adoptedRoster, adoptedRosterHash, preparedRoster)
                        .isEmpty()) {
            return false;
        }
        if (!historyEnabled) {
            return true;
        }
        final var history = historyStore.getActiveConstruction();
        if (!history.hasTargetProof() || !history.targetRosterHash().equals(active.targetRosterHash())) {
            return false;
        }
        final var proof = history.targetProofOrThrow();
        return isWrapsExtensible(proof)
                && proof.hasChainOfTrustProof()
                && proof.hasTargetHistory()
                && proof.targetHistoryOrThrow()
                        .metadata()
                        .equals(active.hintsSchemeOrThrow()
                                .preprocessedKeysOrThrow()
                                .verificationKey());
    }

    /**
     * Resolves the cryptographic target for an adoption that may override transport details.
     * A different roster hash is accepted only with the complete prepared roster as evidence that
     * every node ID and weight is unchanged. The returned hash still has to pass all handoff checks.
     */
    public static @NonNull Optional<Bytes> preparedRosterHashForAdoption(
            @NonNull final Bytes preparedRosterHash,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash,
            @Nullable final Roster preparedRoster) {
        requireNonNull(preparedRosterHash);
        requireNonNull(adoptedRoster);
        requireNonNull(adoptedRosterHash);
        if (preparedRosterHash.equals(adoptedRosterHash)) {
            return Optional.of(preparedRosterHash);
        }
        if (preparedRoster == null
                || !RosterUtils.hash(preparedRoster).getBytes().equals(preparedRosterHash)) {
            return Optional.empty();
        }
        final var preparedWeights =
                preparedRoster.rosterEntries().stream().collect(toMap(RosterEntry::nodeId, RosterEntry::weight));
        final var adoptedWeights =
                adoptedRoster.rosterEntries().stream().collect(toMap(RosterEntry::nodeId, RosterEntry::weight));
        return preparedWeights.equals(adoptedWeights) ? Optional.of(preparedRosterHash) : Optional.empty();
    }

    /**
     * Persists the verified adopted roster identity so a later transport-only override can prove
     * equivalence even after the canonical prepared roster has been pruned. This changes only the
     * completed active constructions' target bookkeeping, never their sources or cryptographic material.
     */
    public static boolean rebindActiveTargetsForAdoption(
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final Roster previousRoster,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash,
            final boolean historyEnabled,
            @Nullable final Roster preparedRoster) {
        if (!canRetainActiveConstruction(
                hintsStore,
                historyStore,
                previousRoster,
                adoptedRoster,
                adoptedRosterHash,
                historyEnabled,
                preparedRoster)) {
            return false;
        }
        final var oldHash = hintsStore.getActiveConstruction().targetRosterHash();
        // The retention preflight checked both completed identities before either service is written.
        if (oldHash.equals(adoptedRosterHash)) {
            return true;
        }
        if (historyEnabled && !historyStore.rebindActiveTargetRosterHash(oldHash, adoptedRosterHash)) {
            return false;
        }
        if (!hintsStore.rebindActiveTargetRosterHash(oldHash, adoptedRosterHash)) {
            throw new IllegalStateException("Guarded active hinTS target rebinding failed");
        }
        return true;
    }

    /** Forced handoff obeys the same CRS and proof binding requirements as a normal handoff. */
    public static boolean tryForcedJointHandoff(
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final HistoryService historyService,
            @NonNull final HintsService hintsService,
            @NonNull final Roster previousRoster,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash) {
        return tryJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                previousRoster,
                adoptedRoster,
                adoptedRosterHash);
    }

    private static boolean promoteTogether(
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final HistoryService historyService,
            @NonNull final HintsService hintsService,
            @NonNull final Roster previousRoster,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash,
            @NonNull final HistoryProof proof) {
        if (!historyStore.handoff(previousRoster, adoptedRoster, adoptedRosterHash)) {
            log.warn("Skipping forced TSS handoff because history construction did not promote");
            return false;
        }
        if (!hintsService.handoff(hintsStore, previousRoster, adoptedRoster, adoptedRosterHash, true)) {
            throw new IllegalStateException("Guarded hinTS handoff failed after history promotion");
        }
        historyService.setLatestHistoryProof(proof);
        return true;
    }

    private static @NonNull Optional<HistoryProof> matchingCompletedHistoryProof(
            @NonNull final ReadableHistoryStore historyStore, @NonNull final ReadableHintsStore hintsStore) {
        final var hintsConstruction = hintsStore.getNextConstruction();
        final var historyConstruction = historyStore.getNextConstruction();
        if (!hintsConstruction.hasHintsScheme()) {
            log.warn(
                    "Skipping forced TSS handoff because next hinTS construction #{} is incomplete",
                    hintsConstruction.constructionId());
            return Optional.empty();
        }
        if (!HistoryService.isCompleted(historyConstruction)) {
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
}
