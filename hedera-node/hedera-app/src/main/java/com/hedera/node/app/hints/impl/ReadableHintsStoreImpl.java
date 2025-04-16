// SPDX-License-Identifier: Apache-2.0
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
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
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
    private final ReadableEntityCounters entityCounters;

    public ReadableHintsStoreImpl(@NonNull final ReadableStates states, final ReadableEntityCounters entityCounters) {
        requireNonNull(states);
        this.hintsKeys = states.get(HINTS_KEY_SETS_KEY);
        this.nextConstruction = states.getSingleton(NEXT_HINT_CONSTRUCTION_KEY);
        this.activeConstruction = states.getSingleton(ACTIVE_HINT_CONSTRUCTION_KEY);
        this.votes = states.get(PREPROCESSING_VOTES_KEY);
        this.crs = states.getSingleton(CRS_STATE_KEY);
        this.crsPublications = states.get(CRS_PUBLICATIONS_KEY);
        this.entityCounters = requireNonNull(entityCounters);
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
        final var nodesSize = entityCounters.getCounterFor(EntityType.NODE);
        final var publications = new ArrayList<CrsPublicationTransactionBody>();
        for (int i = 0; i < nodesSize; i++) {
            final var nodeId = new NodeId(i);
            final var publication = crsPublications.get(nodeId);
            if (publication != null) {
                publications.add(publication);
            }
        }
        return publications;
    }

    @Override
    public Map<Long, CrsPublicationTransactionBody> getOrderedCrsPublications(@NonNull final Set<Long> nodeIds) {
        final Map<Long, CrsPublicationTransactionBody> publications = new HashMap<>();
        for (final var nodeId : nodeIds) {
            final var publication = crsPublications.get(new NodeId(nodeId));
            if (publication != null) {
                publications.put(nodeId, publication);
            }
        }
        return publications;
    }

    private boolean constructionIsFor(
            @NonNull final HintsConstruction construction, @NonNull final ActiveRosters activeRosters) {
        return activeRosters.sourceRosterHash().equals(construction.sourceRosterHash())
                && activeRosters.targetRosterHash().equals(construction.targetRosterHash());
    }
}
