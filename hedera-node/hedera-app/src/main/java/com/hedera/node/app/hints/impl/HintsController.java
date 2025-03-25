// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.OptionalInt;

/**
 * Manages the work needed to advance toward completion of a hinTS construction, if this completion is possible.
 */
public interface HintsController {
    /**
     * Returns the ID of the hinTS construction that this controller is managing.
     */
    long constructionId();

    /**
     * Returns if the construction is still in progress.
     */
    boolean isStillInProgress();

    /**
     * Returns whether the construction has the given party size.
     */
    boolean hasNumParties(int numParties);

    /**
     * Acts relative to the given state to let this node help advance the ongoing hinTS construction toward a
     * deterministic completion, if possible.
     *
     * @param now        the current consensus time
     * @param hintsStore the hints store, in case the controller is able to complete the construction
     * @param isActive   if the platform is active
     */
    void advanceConstruction(@NonNull Instant now, @NonNull WritableHintsStore hintsStore, boolean isActive);

    /**
     * Advances the ongoing CRS work, if possible. This is only relevant when TSS is enabled or on genesis
     * when the network is gathering contributions to construct CRS.
     *
     * @param now                   the current consensus time
     * @param hintsStore            the hints store
     * @param isActive              if the platform is active
     */
    void advanceCrsWork(@NonNull Instant now, @NonNull WritableHintsStore hintsStore, boolean isActive);

    /**
     * Returns the expected party id for the given node id, if available.
     *
     * @param nodeId the node ID
     * @return the party ID, if available
     */
    @NonNull
    OptionalInt partyIdOf(long nodeId);

    /**
     * Adds a new hinTS key publication to the controller's state, if it is relevant to the
     * ongoing construction.
     *
     * @param publication the hint key publication
     * @param crs
     */
    void addHintsKeyPublication(@NonNull ReadableHintsStore.HintsKeyPublication publication, final Bytes crs);

    /**
     * If this controller's construction is not already complete, considers updating its state with this preprocessing
     * vote. Returns true if any state was updated.
     * <p>
     * <b>Important:</b> If this vote results in an output having at least 1/3 of consensus weight, also updates
     * <i>network</i> state in the given writable store with the winning preprocessing output.
     * @param nodeId the node ID
     * @param vote the preprocessing outputs vote
     * @param hintsStore the hints store
     */
    boolean addPreprocessingVote(long nodeId, @NonNull PreprocessingVote vote, @NonNull WritableHintsStore hintsStore);

    /**
     * Cancels any pending work that this controller has scheduled.
     */
    void cancelPendingWork();

    /**
     * Adds a CRS publication to the controller's state, if the network is still gathering contributions.
     *
     * @param publication the CRS publication
     * @param creatorId
     */
    void addCrsPublication(
            @NonNull CrsPublicationTransactionBody publication,
            @NonNull Instant consensusTime,
            @NonNull WritableHintsStore hintsStore,
            final long creatorId);

    /**
     * Verifies the given CRS update.
     *
     * @param publication the publication
     * @param hintsStore  the hints store
     * @param creatorId
     */
    void verifyCrsUpdate(
            @NonNull CrsPublicationTransactionBody publication,
            @NonNull ReadableHintsStore hintsStore,
            final long creatorId);
}
