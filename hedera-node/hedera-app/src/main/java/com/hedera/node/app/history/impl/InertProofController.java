// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.config.data.TssConfig;
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
    public boolean isStillInProgress(@NonNull final TssConfig tssConfig) {
        requireNonNull(tssConfig);
        return false;
    }

    @Override
    public void advanceConstruction(
            @NonNull final Instant now,
            @Nullable final Bytes metadata,
            @NonNull final WritableHistoryStore historyStore,
            final boolean isActive,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(now);
        requireNonNull(historyStore);
        requireNonNull(tssConfig);
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
    public boolean addWrapsMessagePublication(
            @NonNull final WrapsMessagePublication publication,
            @NonNull final WritableHistoryStore writableHistoryStore) {
        requireNonNull(publication);
        requireNonNull(writableHistoryStore);
        return false;
    }

    @Override
    public void cancelPendingWork() {
        // No-op
    }
}
