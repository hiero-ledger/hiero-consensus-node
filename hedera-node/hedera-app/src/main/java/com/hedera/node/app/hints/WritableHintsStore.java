// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Map;

/**
 * Provides write access to the {@link HintsConstruction} instances in state.
 */
public interface WritableHintsStore extends ReadableHintsStore {
    /**
     * If there is a known construction matching the active rosters, returns it; otherwise, null.
     * @param activeRosters the active rosters
     * @param now the current time
     * @param tssConfig the TSS configuration
     */
    @NonNull
    HintsConstruction getOrCreateConstruction(
            @NonNull ActiveRosters activeRosters, @NonNull Instant now, @NonNull TssConfig tssConfig);

    /** Stores a key in the namespace of the CRS generation that produced it. */
    boolean setHintsKey(
            long nodeId, int partyId, int numParties, long crsId, @NonNull Bytes hintsKey, @NonNull Instant now);

    /**
     * Adds a preprocessing vote for the given node and construction.
     */
    void addPreprocessingVote(long nodeId, long constructionId, @NonNull PreprocessingVote vote);

    /**
     * Sets the consensus preprocessing output for the construction with the given ID and returns the
     * updated construction.
     *
     * @return the updated construction
     */
    HintsConstruction setHintsScheme(
            long constructionId,
            @NonNull PreprocessedKeys keys,
            @NonNull Map<Long, Integer> nodePartyIds,
            @NonNull Map<Long, Long> nodeWeights);

    /**
     * Sets the preprocessing start time for the construction with the given ID and returns the updated construction.
     *
     * @param constructionId the construction ID
     * @param now            the aggregation time
     * @return the updated construction
     */
    HintsConstruction setPreprocessingStartTime(long constructionId, @NonNull Instant now);

    /**
     * Updates state for a handoff to the given roster hash only when its CRS and hinTS scheme
     * are complete and meet the adopted roster's capacity requirements.
     *
     * @param fromRoster the previous roster
     * @param toRoster the adopted roster
     * @param toRosterHash the adopted roster hash
     * @return whether the handoff changed the hinTS scheme
     */
    boolean handoff(@NonNull Roster fromRoster, @NonNull Roster toRoster, @NonNull Bytes toRosterHash);

    /**
     * Rebinds a completed active construction after the caller verifies that the adopted roster has
     * exactly the same node IDs and weights as its prepared target. Changes no cryptographic material
     * or CRS provenance. Returns false without mutation if the expected active target is not complete.
     */
    boolean rebindActiveTargetRosterHash(@NonNull Bytes expectedOldHash, @NonNull Bytes newHash);

    /**
     * Sets the {@link CRSState} for the network.
     *
     * @param crsState the {@link CRSState} to set
     */
    void setCrsState(@NonNull CRSState crsState);

    /** Replaces the next CRS envelope without changing the active signing CRS. */
    void setNextCrsState(@NonNull CRSState crsState);

    /** Abandons candidate work without reusing its construction or ceremony identifiers. */
    void abandonNextConstruction();

    /** Updates only the CRS generation bound to the given construction. */
    void setCrsStateFor(@NonNull HintsConstruction construction, @NonNull CRSState crsState);

    /** Allocates a never-reused ceremony ID, persisting the high-water mark in the active envelope. */
    long allocateCrsId();

    /** Binds an unstarted construction to its effective party capacity and CRS generation. */
    HintsConstruction bindConstructionToCrs(long constructionId, long crsId, int numParties);

    /** Opens key collection after the construction's bound CRS completes. */
    HintsConstruction startHintsKeyGracePeriod(long constructionId, @NonNull Instant end);

    /** Opens collection and rotates queued keys at the given consensus time. */
    HintsConstruction startHintsKeyGracePeriod(long constructionId, @NonNull Instant now, @NonNull Instant end);
}
