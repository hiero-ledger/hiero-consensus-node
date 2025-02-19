/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.hints.impl;

import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.WritableHintsStore;
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
     * @param now the current consensus time
     * @param hintsStore the hints store, in case the controller is able to complete the construction
     */
    void advanceConstruction(@NonNull Instant now, @NonNull WritableHintsStore hintsStore);

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
     */
    void addHintsKeyPublication(@NonNull ReadableHintsStore.HintsKeyPublication publication);

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
     * @param publication the CRS publication
     */
    void addCrsPublication(
            @NonNull CrsPublicationTransactionBody publication,
            @NonNull Instant consensusTime,
            @NonNull WritableHintsStore hintsStore);

    /**
     * Verifies the given CRS update.
     * @param publication the publication
     * @param hintsStore the hints store
     */
    void verifyCrsUpdate(@NonNull CrsPublicationTransactionBody publication, @NonNull WritableHintsStore hintsStore);
}
