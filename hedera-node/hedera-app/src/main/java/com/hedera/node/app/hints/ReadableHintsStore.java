// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.node.app.hints.HintsLibrary.isSupportedPartySize;
import static com.hedera.node.app.hints.HintsLibrary.isValidPartyId;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gives read access to the primary and secondary hinTS service states.
 */
public interface ReadableHintsStore {

    /**
     * The full record of a hinTS key publication, including the key, the time it was adopted, the submitting node id,
     * and the party id the node claimed to have for the construction. (For validating its submission.)
     *
     * @param nodeId       the id of the node submitting the key
     * @param hintsKey     the hinTS key itself
     * @param partyId      the party id the node claimed to have for the construction
     * @param adoptionTime the time at which the key was adopted
     */
    record HintsKeyPublication(
            long nodeId,
            @NonNull Bytes hintsKey,
            int partyId,
            @NonNull Instant adoptionTime) {
        public HintsKeyPublication {
            requireNonNull(hintsKey);
            requireNonNull(adoptionTime);
        }
    }

    /**
     * Returns the active construction.
     */
    @NonNull
    HintsConstruction getActiveConstruction();

    /**
     * Returns the next construction.
     */
    @NonNull
    HintsConstruction getNextConstruction();

    /**
     * Returns whether the give roster hash is ready to be adopted.
     *
     * @param rosterHash the roster hash
     * @return whether the give roster hash is ready to be adopted
     */
    default boolean isReadyToAdopt(@NonNull final Bytes rosterHash) {
        final var construction = getNextConstruction();
        final var crs = getCrsStateFor(construction);
        return construction.hasHintsScheme()
                && construction.targetRosterHash().equals(rosterHash)
                && construction.crsId() > 0
                && isSupportedPartySize(construction.numParties())
                && construction.hintsSchemeOrThrow().nodePartyIds().stream()
                        .allMatch(p -> isValidPartyId(p.partyId(), construction.numParties()))
                && crs.stage() == COMPLETED
                && crs.crs().length() > 0
                && crs.numParties() == construction.numParties();
    }

    /**
     * Returns the verification key for the current roster, if known.
     *
     * @return the verification key, or null if not yet known
     */
    @Nullable
    Bytes getActiveVerificationKey();

    /**
     * If there is a known construction matching the active rosters, returns it; otherwise, null.
     *
     * @param activeRosters the active rosters
     */
    @Nullable
    HintsConstruction getConstructionFor(@NonNull ActiveRosters activeRosters);

    /**
     * Returns all known preprocessing output votes from the given nodes for the given construction id.
     *
     * @param constructionId the construction id
     * @param nodeIds        the node ids
     * @return the preprocessed keys and votes, or null
     */
    @NonNull
    Map<Long, PreprocessingVote> getVotes(long constructionId, @NonNull Set<Long> nodeIds);

    /** Returns publications bound to exactly the requested CRS generation and party capacity. */
    @NonNull
    List<HintsKeyPublication> getHintsKeyPublications(@NonNull Set<Long> nodeIds, int numParties, long crsId);

    /**
     * Returns the current CRS state.
     *
     * @return the current CRS state
     */
    CRSState getCrsState();

    /** Returns the isolated next CRS ceremony, or the default state when there is none. */
    @NonNull
    CRSState getNextCrsState();

    /** Returns the CRS bound to a construction; unbound or stale references return the default state. */
    default @NonNull CRSState getCrsStateFor(@NonNull final HintsConstruction construction) {
        if (construction.crsId() <= 0 || !isSupportedPartySize(construction.numParties())) {
            return CRSState.DEFAULT;
        }
        final var active = getCrsState();
        if (active.ceremonyId() == construction.crsId() && active.numParties() == construction.numParties()) {
            return active;
        }
        final var next = getNextCrsState();
        if (next.ceremonyId() == construction.crsId()
                && next.numParties() == construction.numParties()
                && next.constructionId() == construction.constructionId()
                && next.sourceRosterHash().equals(construction.sourceRosterHash())
                && next.targetRosterHash().equals(construction.targetRosterHash())) {
            return next;
        }
        return CRSState.DEFAULT;
    }
}
