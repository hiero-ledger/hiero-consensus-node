// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.node.app.history.ReadableHistoryStore.HistorySignaturePublication;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Manages the process objects and work needed to advance work towards a proof that a certain
 * {@code (address book hash, metadata)} pair belongs to the chain of trust proceeding from the
 * ledger id. (Or, if the ledger id is null, simply the proof that the ledger has blessed the
 * genesis address book metadata).
 */
public interface ProofController {
    /**
     * Returns the ID of the proof construction that this controller is managing.
     */
    long constructionId();

    /**
     * Returns if the construction is still in progress.
     */
    boolean isStillInProgress();

    /**
     * Acts relative to the given state to let this node help advance the ongoing metadata proof
     * construction toward a deterministic completion.
     *
     * @param now                   the current consensus time
     * @param metadata              the latest known metadata to be proven
     * @param historyStore          the history store, in case the controller is able to complete the construction
     * @param isActive              if platform is active
     */
    void advanceConstruction(
            @NonNull Instant now,
            @Nullable Bytes metadata,
            @NonNull WritableHistoryStore historyStore,
            boolean isActive);

    /**
     * Incorporates the proof key published by the given node, if this construction has not already "locked in"
     * its assembled {@code (address book hash, metadata)} history.
     *
     * @param publication the proof key publication
     */
    void addProofKeyPublication(@NonNull ProofKeyPublication publication);

    /**
     * If this controller's construction is not already complete, considers updating its state with this history
     * proof vote. Returns true if any state was updated.
     * <p>
     * <b>Important:</b> If this vote results in an output having at least 1/3 of consensus weight, also updates
     * <i>network</i> state in the given writable store with the winning proof.
     *
     * @param nodeId the node ID
     * @param vote the history proof vote
     * @param historyStore the history store
     */
    void addProofVote(long nodeId, @NonNull HistoryProofVote vote, @NonNull WritableHistoryStore historyStore);

    /**
     * Incorporates the given history signature, if this construction still needs a proof, and does
     * not already have a signature published by the associated node.
     *
     * @param publication the proof key publication
     * @return if the signature was added
     */
    boolean addSignaturePublication(@NonNull HistorySignaturePublication publication);

    /**
     * Cancels any pending work that this controller has scheduled.
     */
    void cancelPendingWork();
}
