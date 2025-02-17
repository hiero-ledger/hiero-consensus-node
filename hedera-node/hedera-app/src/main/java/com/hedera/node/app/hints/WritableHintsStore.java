/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.hints;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
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
    boolean setHintsKey(long nodeId, int partyId, int numParties, @NonNull Bytes hintsKey, @NonNull final Instant now);

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
            long constructionId, @NonNull PreprocessedKeys keys, @NonNull Map<Long, Integer> nodePartyIds);

    /**
     * Sets the preprocessing start time for the construction with the given ID and returns the updated construction.
     *
     * @param constructionId the construction ID
     * @param now            the aggregation time
     * @return the updated construction
     */
    HintsConstruction setPreprocessingStartTime(long constructionId, @NonNull Instant now);

    /**
     * Purges any state no longer needed after a given handoff.
     */
    void updateForHandoff(@NonNull ActiveRosters activeRosters);

    /**
     * Sets the {@link CRSState} for the network.
     *
     * @param crsState the {@link CRSState} to set
     */
    void setCRSState(@NonNull CRSState crsState);

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
