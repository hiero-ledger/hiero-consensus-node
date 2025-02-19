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

package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.node.app.history.ReadableHistoryStore.HistorySignaturePublication;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

public class InertProofController implements ProofController {
    private final long constructionId;

    public InertProofController(final long constructionId) {
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
    public void advanceConstruction(
            @NonNull final Instant now,
            @Nullable final Bytes metadata,
            @NonNull final WritableHistoryStore historyStore) {
        requireNonNull(now);
        requireNonNull(historyStore);
        // No-op
    }

    @Override
    public void addProofKeyPublication(@NonNull final ProofKeyPublication publication) {
        requireNonNull(publication);
        // No-op
    }

    @Override
    public void addProofVote(
            final long nodeId, @NonNull final HistoryProofVote vote, @NonNull final WritableHistoryStore historyStore) {
        requireNonNull(vote);
        requireNonNull(historyStore);
        // No-op
    }

    @Override
    public boolean addSignaturePublication(@NonNull final HistorySignaturePublication publication) {
        requireNonNull(publication);
        return false;
    }

    @Override
    public void cancelPendingWork() {
        // No-op
    }
}
