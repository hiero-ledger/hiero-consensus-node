// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
    public void advanceConstruction(
            @NonNull final Instant now, @NonNull final WritableHintsStore hintsStore, final boolean isActive) {
        requireNonNull(now);
        requireNonNull(hintsStore);
        // No-op
    }

    @Override
    public void advanceCrsWork(
            @NonNull final Instant now,
            @NonNull final WritableHintsStore hintsStore,
            final boolean currentPlatformStatus) {
        requireNonNull(now);
        requireNonNull(hintsStore);
    }

    @NonNull
    @Override
    public OptionalInt partyIdOf(final long nodeId) {
        return OptionalInt.empty();
    }

    @Override
    public void addHintsKeyPublication(
            @NonNull final ReadableHintsStore.HintsKeyPublication publication, final Bytes crs) {
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
            @NonNull final WritableHintsStore hintsStore,
            final long creatorId) {
        requireNonNull(publication);
        requireNonNull(consensusTime);
        requireNonNull(hintsStore);
        // No-op
    }

    @Override
    public void verifyCrsUpdate(
            @NonNull final CrsPublicationTransactionBody publication,
            @NonNull final ReadableHintsStore hintsStore,
            final long creatorId) {
        requireNonNull(publication);
        requireNonNull(hintsStore);
        // No-op
    }
}
