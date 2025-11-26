// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.WrapsMessageDetails;
import com.hedera.hapi.node.state.history.WrapsMessageHistory;
import com.hedera.hapi.node.state.history.WrapsPhase;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Comparator;
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
     * The record of a WRAPS message publication, including the message, the WRAPS phase it was targeted for,
     * the time it was received, and the submitting node id.
     * @param message the WRAPS message
     * @param phase the WRAPS phase
     * @param nodeId the node ID submitting the WRAPS message
     */
    record WrapsMessagePublication(
            long nodeId, @NonNull Bytes message, @NonNull WrapsPhase phase, @NonNull Instant receiptTime)
            implements Comparable<WrapsMessagePublication> {
        public WrapsMessagePublication {
            requireNonNull(message);
            requireNonNull(phase);
            requireNonNull(receiptTime);
        }

        @Override
        public int compareTo(@NonNull final WrapsMessagePublication that) {
            return Comparator.comparing(WrapsMessagePublication::receiptTime).compare(this, that);
        }

        /**
         * Unwraps all the WRAPS message publications from the given history, scoped to the given node.
         * @param nodeId the node ID
         * @param history the history
         * @return the WRAPS message publications
         */
        public static List<WrapsMessagePublication> allFromHistory(
                final long nodeId, @NonNull final WrapsMessageHistory history) {
            return history.messages().stream()
                    .map(details -> new WrapsMessagePublication(
                            nodeId, details.message(), details.phase(), asInstant(details.publicationTimeOrThrow())))
                    .toList();
        }

        /**
         * Returns this publication as a {@link WrapsMessageDetails}.
         */
        public WrapsMessageDetails asWrapsMessageDetails() {
            return new WrapsMessageDetails(asTimestamp(receiptTime), phase, message);
        }
    }

    /**
     * Returns the ledger id initiating the chain of trusted history, if known.
     */
    @Nullable
    Bytes getLedgerId();

    /**
     * Gets the construction with the given ID, throwing if it does not exist.
     */
    @NonNull
    HistoryProofConstruction getConstructionOrThrow(long constructionId);

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
     * Returns the WRAPS messages published by the given set of nodes for the given construction.
     * @param constructionId the construction id
     * @param nodeIds the node ids
     * @return the {@link WrapsMessagePublication}s
     */
    @NonNull
    List<WrapsMessagePublication> getWrapsMessagePublications(long constructionId, @NonNull Set<Long> nodeIds);
}
