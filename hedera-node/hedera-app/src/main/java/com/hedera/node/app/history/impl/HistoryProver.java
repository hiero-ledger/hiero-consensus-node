// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.node.app.history.ReadableHistoryStore.HistorySignaturePublication;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates the responsibility of constructing a {@link HistoryProof} for a single {@link HistoryProofConstruction}.
 * The inputs are,
 * <ul>
 *   <li>The target roster and proof keys; and,</li>
 *   <li>Schnorr signatures from source nodes; and,</li>
 *   <li>The target metadata.</li>
 * </ul>
 * <p>
 * Implementations are allowed to be completely asynchronous internally, and most implementations will likely converge
 * to an outcome by submitting votes via {@link HistorySubmissions#submitProofVote(long, HistoryProof)}. However, a
 * simple implementation could also return a completed proof from a synchronous call to {@link #advance}.
 */
public interface HistoryProver {
    Comparator<ProofKey> PROOF_KEY_COMPARATOR = Comparator.comparingLong(ProofKey::nodeId);

    /**
     * State of the prover.
     */
    sealed interface Outcome {
        /**
         * Prover is still working; nothing terminal has happened yet.
         */
        final class InProgress implements Outcome {
            public static final InProgress INSTANCE = new InProgress();

            private InProgress() {}
        }

        /**
         * Prover has completed and produced a {@link HistoryProof}.
         */
        record Completed(@NonNull HistoryProof proof) implements Outcome {}

        /**
         * Prover has irrecoverably failed for the given reason.
         * The controller should deterministically fail the construction with this reason.
         */
        record Failed(@NonNull String reason) implements Outcome {}
    }

    /**
     * Informs the prover of a new history signature publication that has reached consensus.
     * Implementations decide if the publication is relevant.
     * @param publication the signature publication
     * @param targetProofKeys current snapshot of nodeId -> Schnorr proof key for the target roster
     * @return true if the publication was needed by this prover, false otherwise
     */
    default boolean addSignaturePublication(
            @NonNull HistorySignaturePublication publication, @NonNull Map<Long, Bytes> targetProofKeys) {
        return false;
    }

    /**
     * Drive the prover forward one step. This is called from {@link ProofController#advanceConstruction} only after,
     * <ul>
     *   <li>The target metadata is known, and</li>
     *   <li>The assembly start time has been set.</li>
     * </ul>
     * Implementations then derive the proof asynchronously to completion.
     * <p>
     * If the prover concludes that success is impossible (e.g. too many invalid signatures or not enough remaining
     * weight), it should return {@link Outcome.Failed} with a deterministic reason string.
     * @param now current consensus time
     * @param construction current construction state
     * @param targetMetadata metadata to attach to the target roster
     * @param targetProofKeys current snapshot of nodeId -> Schnorr proof key for the target roster
     * @return the current outcome of proof construction
     */
    @NonNull
    Outcome advance(
            @NonNull Instant now,
            @NonNull HistoryProofConstruction construction,
            @NonNull Bytes targetMetadata,
            @NonNull Map<Long, Bytes> targetProofKeys);

    /**
     * Cancel any in-flight asynchronous work started by this prover.
     * @return true if something was actually cancelled
     */
    boolean cancelPendingWork();

    /**
     * Returns a list of proof keys from the given map.
     * @param proofKeys the proof keys in a map
     * @return the list of proof keys
     */
    default List<ProofKey> proofKeyListFrom(@NonNull final Map<Long, Bytes> proofKeys) {
        return proofKeys.entrySet().stream()
                .map(entry -> new ProofKey(entry.getKey(), entry.getValue()))
                .sorted(PROOF_KEY_COMPARATOR)
                .toList();
    }
}
