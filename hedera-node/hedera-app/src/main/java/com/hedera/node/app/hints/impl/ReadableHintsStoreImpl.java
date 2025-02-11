/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINT_CONSTRUCTION_KEY;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.HINTS_KEY_SETS_KEY;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINT_CONSTRUCTION_KEY;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.PREPROCESSING_VOTES_KEY;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_PUBLICATIONS_KEY;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.hints.PreprocessingVoteId;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

/**
 * Provides read access to the {@link HintsConstruction} and {@link PreprocessingVote} instances in state.
 */
public class ReadableHintsStoreImpl implements ReadableHintsStore {
    private final ReadableKVState<HintsPartyId, HintsKeySet> hintsKeys;
    private final ReadableSingletonState<HintsConstruction> nextConstruction;
    private final ReadableSingletonState<HintsConstruction> activeConstruction;
    private final ReadableKVState<PreprocessingVoteId, PreprocessingVote> votes;
    private final ReadableSingletonState<CRSState> crs;
    private final ReadableKVState<NodeId, CrsPublicationTransactionBody> crsPublications;

    public ReadableHintsStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.hintsKeys = states.get(HINTS_KEY_SETS_KEY);
        this.nextConstruction = states.getSingleton(NEXT_HINT_CONSTRUCTION_KEY);
        this.activeConstruction = states.getSingleton(ACTIVE_HINT_CONSTRUCTION_KEY);
        this.votes = states.get(PREPROCESSING_VOTES_KEY);
        this.crs = states.getSingleton(CRS_STATE_KEY);
        this.crsPublications = states.get(CRS_PUBLICATIONS_KEY);
    }

    @Override
    public @Nullable Bytes getActiveVerificationKey() {
        final var construction = requireNonNull(activeConstruction.get());
        if (construction.hasHintsScheme()) {
            return construction.hintsSchemeOrThrow().preprocessedKeysOrThrow().verificationKey();
        }
        return null;
    }

    @Override
    public @NonNull HintsConstruction getActiveConstruction() {
        return requireNonNull(activeConstruction.get());
    }

    @Override
    public @NonNull HintsConstruction getNextConstruction() {
        return requireNonNull(nextConstruction.get());
    }

    @Override
    public @Nullable HintsConstruction getConstructionFor(@NonNull final ActiveRosters activeRosters) {
        requireNonNull(activeRosters);
        return switch (activeRosters.phase()) {
            case BOOTSTRAP, TRANSITION -> {
                HintsConstruction construction;
                if (constructionIsFor(construction = requireNonNull(activeConstruction.get()), activeRosters)) {
                    yield construction;
                } else if (constructionIsFor(construction = requireNonNull(nextConstruction.get()), activeRosters)) {
                    yield construction;
                }
                yield null;
            }
            case HANDOFF -> null;
        };
    }

    @Override
    public @NonNull Map<Long, PreprocessingVote> getVotes(final long constructionId, @NonNull final Set<Long> nodeIds) {
        requireNonNull(nodeIds);
        final Map<Long, PreprocessingVote> constructionVotes = new HashMap<>();
        for (final var nodeId : nodeIds) {
            final var vote = votes.get(new PreprocessingVoteId(constructionId, nodeId));
            if (vote != null) {
                constructionVotes.put(nodeId, vote);
            }
        }
        return constructionVotes;
    }

    @Override
    public @NonNull List<HintsKeyPublication> getHintsKeyPublications(
            @NonNull final Set<Long> nodeIds, final int numParties) {
        requireNonNull(nodeIds);
        final List<HintsKeyPublication> publications = new ArrayList<>();
        for (int partyId = 0; partyId < numParties; partyId++) {
            final var keySet = hintsKeys.get(new HintsPartyId(partyId, numParties));
            if (keySet != null) {
                if (nodeIds.contains(keySet.nodeId())) {
                    publications.add(new HintsKeyPublication(
                            keySet.nodeId(), keySet.key(), partyId, asInstant(keySet.adoptionTimeOrThrow())));
                }
            }
        }
        return publications;
    }

    @Override
    public @NonNull CRSState getCrsState() {
        return requireNonNull(crs.get());
    }

    @Override
    public List<CrsPublicationTransactionBody> getCrsPublications() {
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(crsPublications.keys(), Spliterator.ORDERED), false)
                .map(crsPublications::get)
                .toList();
    }

    private boolean constructionIsFor(
            @NonNull final HintsConstruction construction, @NonNull final ActiveRosters activeRosters) {
        return activeRosters.sourceRosterHash().equals(construction.sourceRosterHash())
                && activeRosters.targetRosterHash().equals(construction.targetRosterHash());
    }
}
