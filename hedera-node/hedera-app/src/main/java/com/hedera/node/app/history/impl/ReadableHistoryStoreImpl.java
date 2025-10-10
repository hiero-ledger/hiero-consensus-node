// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.history.schemas.V059HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V059HistorySchema.HISTORY_SIGNATURES_STATE_ID;
import static com.hedera.node.app.history.schemas.V059HistorySchema.LEDGER_ID_STATE_ID;
import static com.hedera.node.app.history.schemas.V059HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V059HistorySchema.PROOF_KEY_SETS_STATE_ID;
import static com.hedera.node.app.history.schemas.V059HistorySchema.PROOF_VOTES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.ConstructionNodeId;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.ProofKeySet;
import com.hedera.hapi.node.state.history.RecordedHistorySignature;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
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
 * Default implementation of {@link ReadableHistoryStore}.
 */
public class ReadableHistoryStoreImpl implements ReadableHistoryStore {

    private final ReadableSingletonState<ProtoBytes> ledgerId;
    private final ReadableSingletonState<HistoryProofConstruction> nextConstruction;
    private final ReadableSingletonState<HistoryProofConstruction> activeConstruction;
    private final ReadableKVState<NodeId, ProofKeySet> proofKeySets;
    private final ReadableKVState<ConstructionNodeId, RecordedHistorySignature> signatures;
    private final ReadableKVState<ConstructionNodeId, HistoryProofVote> votes;

    public ReadableHistoryStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.ledgerId = states.getSingleton(LEDGER_ID_STATE_ID);
        this.nextConstruction = states.getSingleton(NEXT_PROOF_CONSTRUCTION_STATE_ID);
        this.activeConstruction = states.getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID);
        this.proofKeySets = states.get(PROOF_KEY_SETS_STATE_ID);
        this.signatures = states.get(HISTORY_SIGNATURES_STATE_ID);
        this.votes = states.get(PROOF_VOTES_STATE_ID);
    }

    @Override
    public @Nullable Bytes getLedgerId() {
        final var maybeLedgerId = requireNonNull(ledgerId.get()).value();
        return Bytes.EMPTY.equals(maybeLedgerId) ? null : maybeLedgerId;
    }

    @Override
    public @NonNull HistoryProofConstruction getActiveConstruction() {
        return requireNonNull(activeConstruction.get());
    }

    @Override
    public @NonNull HistoryProofConstruction getNextConstruction() {
        return requireNonNull(nextConstruction.get());
    }

    @Override
    public @Nullable HistoryProofConstruction getConstructionFor(@NonNull final ActiveRosters activeRosters) {
        requireNonNull(activeRosters);
        return switch (activeRosters.phase()) {
            case BOOTSTRAP, TRANSITION -> {
                HistoryProofConstruction construction;
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
    public @NonNull Map<Long, HistoryProofVote> getVotes(final long constructionId, @NonNull final Set<Long> nodeIds) {
        requireNonNull(nodeIds);
        final Map<Long, HistoryProofVote> constructionVotes = new HashMap<>();
        for (final var nodeId : nodeIds) {
            final var vote = votes.get(new ConstructionNodeId(constructionId, nodeId));
            if (vote != null) {
                constructionVotes.put(nodeId, vote);
            }
        }
        return constructionVotes;
    }

    @Override
    public @NonNull List<ProofKeyPublication> getProofKeyPublications(@NonNull final Set<Long> nodeIds) {
        requireNonNull(nodeIds);
        final List<ProofKeyPublication> publications = new ArrayList<>();
        nodeIds.forEach(id -> {
            final var keySet = proofKeySets.get(new NodeId(id));
            if (keySet != null) {
                publications.add(new ProofKeyPublication(id, keySet.key(), asInstant(keySet.adoptionTimeOrThrow())));
            }
        });
        return publications;
    }

    @Override
    public @NonNull List<HistorySignaturePublication> getSignaturePublications(
            final long constructionId, @NonNull final Set<Long> nodeIds) {
        requireNonNull(nodeIds);
        final List<HistorySignaturePublication> publications = new ArrayList<>();
        for (final var nodeId : nodeIds) {
            final var recorded = signatures.get(new ConstructionNodeId(constructionId, nodeId));
            if (recorded != null) {
                publications.add(new HistorySignaturePublication(
                        nodeId, recorded.historySignatureOrThrow(), asInstant(recorded.signingTimeOrThrow())));
            }
        }
        return publications;
    }

    private boolean constructionIsFor(
            @NonNull final HistoryProofConstruction construction, @NonNull final ActiveRosters activeRosters) {
        return activeRosters.sourceRosterHash().equals(construction.sourceRosterHash())
                && activeRosters.targetRosterHash().equals(construction.targetRosterHash());
    }
}
