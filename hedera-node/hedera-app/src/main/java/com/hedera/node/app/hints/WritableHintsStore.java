// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;

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

    /**
     * Includes the given hints key for the given node and party IDs relative to a party size, assigning
     * the given adoption time if the key is immediately in use.
     *
     * @param nodeId     the node ID
     * @param partyId    the party ID
     * @param numParties the number of parties
     * @param hintsKey   the hints key to include
     * @param now        the adoption time
     * @return whether the key was immediately in use
     */
    boolean setHintsKey(long nodeId, int partyId, int numParties, @NonNull Bytes hintsKey, @NonNull Instant now);

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
     * Updates state for a handoff to the given roster hash.
     *
     * @param fromRoster the previous roster
     * @param toRoster the adopted roster
     * @param toRosterHash the adopted roster hash
     * @param forceHandoff whether to force the handoff when the adopted roster hash doesn't match the next construction
     * @return whether the handoff changed the hinTS scheme
     */
    boolean handoff(
            @NonNull Roster fromRoster, @NonNull Roster toRoster, @NonNull Bytes toRosterHash, boolean forceHandoff);

    /**
     * Sets the {@link CRSState} for the network.
     *
     * @param crsState the {@link CRSState} to set
     */
    void setCrsState(@NonNull CRSState crsState);

    /**
     * Moves the CRS contribution to be done by the next node in the roster. This is called when the
     * current node did not contribute the CRS in time.
     *
     * @param nextNodeIdFromRoster    the ID of the next node in the roster
     * @param nextContributionTimeEnd the end of the time window for the next contribution
     */
    void moveToNextNode(@NonNull OptionalLong nextNodeIdFromRoster, @NonNull Instant nextContributionTimeEnd);

    /**
     * Adds a CRS publication to the store.
     * @param nodeId the node ID
     * @param crsPublication the CRS publication
     */
    void addCrsPublication(long nodeId, @NonNull CrsPublicationTransactionBody crsPublication);
}
