// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.ProofKeysAccessorImpl.SchnorrKeyPair;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

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
 * to an outcome by submitting votes via {@link HistorySubmissions#submitExplicitProofVote(long, HistoryProof)}. However, a
 * simple implementation could also return a completed proof from a synchronous call to {@link #advance}.
 * <p>
 * Since implementations are expected to also be stateful, a {@link ProofController} will have a {@link HistoryProver}
 * lifecycle that is 1:1 with the {@link HistoryProofConstruction} it is managing.
 */
public interface HistoryProver {
    Comparator<ProofKey> PROOF_KEY_COMPARATOR = Comparator.comparingLong(ProofKey::nodeId);

    @FunctionalInterface
    interface Factory {
        HistoryProver create(
                long selfId,
                @NonNull TssConfig tssConfig,
                @NonNull SchnorrKeyPair schnorrKeyPair,
                @Nullable HistoryProof sourceProof,
                @NonNull RosterTransitionWeights weights,
                @NonNull Map<Long, Bytes> proofKeys,
                @NonNull Executor executor,
                @NonNull HistoryLibrary library,
                @NonNull HistorySubmissions submissions);
    }

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
     * Drive the prover forward one step. This is called from {@link ProofController#advanceConstruction} only after,
     * <ul>
     *   <li>The target metadata is known, and</li>
     *   <li>The assembly start time has been set.</li>
     * </ul>
     * Implementations then derive the proof asynchronously to completion.
     * <p>
     * If the prover concludes that success is impossible (e.g. too many invalid signatures or not enough remaining
     * weight), it should return {@link Outcome.Failed} with a deterministic reason string.
     *
     * @param now current consensus time
     * @param construction current construction state
     * @param targetMetadata metadata to attach to the target roster
     * @param targetProofKeys current snapshot of nodeId -> Schnorr proof key for the target roster
     * @param tssConfig the TSS configuration
     * @param ledgerId the ledger id, if known
     * @return the current outcome of proof construction
     */
    @NonNull
    Outcome advance(
            @NonNull Instant now,
            @NonNull HistoryProofConstruction construction,
            @NonNull Bytes targetMetadata,
            @NonNull Map<Long, Bytes> targetProofKeys,
            @NonNull TssConfig tssConfig,
            @Nullable Bytes ledgerId);

    /**
     * Cancel any in-flight asynchronous work started by this prover.
     * @return true if something was actually cancelled
     */
    boolean cancelPendingWork();

    /**
     * Informs the prover of a new WRAPS message publication that has reached consensus. Implementations decide
     * if the publication is relevant.
     *
     * @param constructionId the construction ID
     * @param publication the WRAPS message publication
     * @param writableHistoryStore the writable history store
     * @return true if the publication was needed by this prover, false otherwise
     */
    boolean addWrapsSigningMessage(
            long constructionId,
            @NonNull WrapsMessagePublication publication,
            @NonNull WritableHistoryStore writableHistoryStore);

    /**
     * Replays a WRAPS message publication that previously reached consensus.
     * @param constructionId the construction ID
     * @param publication the WRAPS message publication
     */
    void replayWrapsSigningMessage(long constructionId, @NonNull WrapsMessagePublication publication);

    /**
     * Observes a proof vote.
     *
     * @param nodeId the node ID
     * @param vote the vote
     * @param proofFinalized whether this vote finalized the proof
     */
    void observeProofVote(long nodeId, @NonNull HistoryProofVote vote, boolean proofFinalized);

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
