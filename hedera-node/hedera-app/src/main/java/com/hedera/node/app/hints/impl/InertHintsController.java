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

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.WritableHintsStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.OptionalInt;

/**
 * A {@link HintsController} that does nothing; the right implementation if a {@link HintsControllerImpl} cannot
 * be constructed. (For example, because the current roster does have strictly greater than 2/3 weight in the
 * candidate roster.)
 */
public class InertHintsController implements HintsController {
    private final long constructionId;

    public InertHintsController(final long constructionId) {
        this.constructionId = constructionId;
    }

    @Override
    public long constructionId() {
        return constructionId;
    }

    @Override
    public boolean isStillInProgress() {
        return false;
    }

    @Override
    public boolean hasNumParties(final int numParties) {
        return false;
    }

    @Override
    public void advanceConstruction(@NonNull final Instant now, @NonNull final WritableHintsStore hintsStore) {
        requireNonNull(now);
        requireNonNull(hintsStore);
        // No-op
    }

    @NonNull
    @Override
    public OptionalInt partyIdOf(final long nodeId) {
        return OptionalInt.empty();
    }

    @Override
    public void addHintsKeyPublication(@NonNull final ReadableHintsStore.HintsKeyPublication publication) {
        requireNonNull(publication);
        // No-op
    }

    @Override
    public boolean addPreprocessingVote(
            final long nodeId, @NonNull final PreprocessingVote vote, @NonNull final WritableHintsStore hintsStore) {
        requireNonNull(vote);
        requireNonNull(hintsStore);
        return false;
    }

    @Override
    public void cancelPendingWork() {
        // No-op
    }

    @Override
    public void addCrsPublication(
            @NonNull final CrsPublicationTransactionBody publication,
            @NonNull final Instant consensusTime,
            @NonNull final WritableHintsStore hintsStore) {
        requireNonNull(publication);
        requireNonNull(consensusTime);
        requireNonNull(hintsStore);
        // No-op
    }

    @Override
    public void verifyCrsUpdate(
            @NonNull final CrsPublicationTransactionBody publication, @NonNull final WritableHintsStore hintsStore) {
        requireNonNull(publication);
        requireNonNull(hintsStore);
        // No-op
    }
}
