// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.HistorySignature;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides read access to the {@link HistoryService} state.
 */
public interface ReadableHistoryStore {
    /**
     * The full record of a proof key publication, including the key, the time it was adopted, and the
     * submitting node id.
     *
     * @param nodeId the node ID submitting the key
     * @param proofKey the proof key itself
     * @param adoptionTime the time at which the key was adopted
     */
    record ProofKeyPublication(long nodeId, @NonNull Bytes proofKey, @NonNull Instant adoptionTime) {
        public ProofKeyPublication {
            requireNonNull(proofKey);
            requireNonNull(adoptionTime);
        }
    }

    /**
     * The full record of a history signature, include the time the signature was published.
     *
     * @param nodeId the node ID submitting the signature
     * @param signature the assembly signature
     * @param at the time at which the signature was published
     */
    record HistorySignaturePublication(long nodeId, @NonNull HistorySignature signature, @NonNull Instant at) {
        public HistorySignaturePublication {
            requireNonNull(signature);
            requireNonNull(at);
        }
    }

    /**
     * Returns the ledger id initiating the chain of trusted history, if known.
     */
    @Nullable
    Bytes getLedgerId();

    /**
     * Returns the active construction.
     */
    @NonNull
    HistoryProofConstruction getActiveConstruction();

    /**
     * Returns the next construction.
     */
    @NonNull
    HistoryProofConstruction getNextConstruction();

    /**
     * Returns whether the give roster hash is ready to be adopted.
     * @param rosterHash the roster hash
     * @return whether the give roster hash is ready to be adopted
     */
    default boolean isReadyToAdopt(@NonNull final Bytes rosterHash) {
        final var construction = getNextConstruction();
        return construction.hasTargetProof() && construction.targetRosterHash().equals(rosterHash);
    }

    /**
     * If there is a known construction matching the active rosters, returns it; otherwise, null.
     */
    @Nullable
    HistoryProofConstruction getConstructionFor(@NonNull ActiveRosters activeRosters);

    /**
     * Returns all known proof votes from the given nodes for the given construction id.
     * @param constructionId the construction id
     * @param nodeIds the node ids
     * @return the preprocessed keys and votes, or null
     */
    @NonNull
    Map<Long, HistoryProofVote> getVotes(long constructionId, @NonNull Set<Long> nodeIds);

    /**
     * Returns the proof keys published by the given set of nodes for the active construction. (That is,
     * if a node published a key rotation after the start of the active construction, that publication
     * will <b>not</b> be in the returned list.)
     * @param nodeIds the node ids
     * @return the {@link ProofKeyPublication}s
     */
    @NonNull
    List<ProofKeyPublication> getProofKeyPublications(@NonNull Set<Long> nodeIds);

    /**
     * Returns the history signatures published by the given set of nodes for the given construction.
     * @param constructionId the construction id
     * @param nodeIds the node ids
     * @return the {@link HistorySignaturePublication}s
     */
    @NonNull
    List<HistorySignaturePublication> getSignaturePublications(long constructionId, @NonNull Set<Long> nodeIds);
}
