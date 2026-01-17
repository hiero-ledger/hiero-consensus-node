// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Provides write access to the {@link HistoryService} state.
 */
public interface WritableHistoryStore extends ReadableHistoryStore {
    /**
     * If there is a known construction matching the active rosters, returns it; otherwise, null.
     * @param activeRosters the active rosters
     * @param now the current time
     * @param tssConfig the TSS configuration
     */
    @NonNull
    HistoryProofConstruction getOrCreateConstruction(
            @NonNull ActiveRosters activeRosters, @NonNull Instant now, @NonNull TssConfig tssConfig);

    /**
     * Includes the given proof key for the given node, assigning the given adoption time if the key
     * is immediately in use.
     *
     * @param nodeId the node ID
     * @param proofKey the hints key to include
     * @param now the adoption time
     * @return whether the key was immediately in use
     */
    boolean setProofKey(long nodeId, @NonNull Bytes proofKey, @NonNull Instant now);

    /**
     * Sets the assembly time for the construction with the given ID and returns the
     * updated construction.
     * @param constructionId the construction ID
     * @param now the aggregation time
     * @return the updated construction
     */
    HistoryProofConstruction setAssemblyTime(long constructionId, @NonNull Instant now);

    /**
     * Adds a node's signature on a particular assembled history proof for the given construction.
     */
    void addSignature(long constructionId, @NonNull HistorySignaturePublication publication);

    /**
     * Adds a history proof vote for the given node and construction.
     */
    void addProofVote(long nodeId, long constructionId, @NonNull HistoryProofVote vote);

    /**
     * Completes the proof for the construction with the given ID and returns the updated construction.
     * @param constructionId the construction ID
     * @param proof the proof
     * @return the updated construction
     */
    HistoryProofConstruction completeProof(long constructionId, @NonNull HistoryProof proof);

    /**
     * Fails the construction with the given ID, providing a reason.
     * @param constructionId the construction ID
     * @param reason the reason
     * @return the updated construction
     */
    HistoryProofConstruction failForReason(long constructionId, @NonNull String reason);

    /**
     * Sets the ledger ID to the given bytes.
     * @param bytes the bytes
     */
    void setLedgerId(@NonNull Bytes bytes);

    /**
     * Hands off from the active construction to the next construction if appropriate.
     * @param fromRoster the roster to hand off from
     * @param toRoster the roster to hand off to
     * @param toRosterHash the hash of the roster to hand off to
     * @return whether the handoff happened
     */
    boolean handoff(@NonNull Roster fromRoster, @NonNull Roster toRoster, @NonNull Bytes toRosterHash);
}
