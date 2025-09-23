// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.history.schemas.V059HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V059HistorySchema.HISTORY_SIGNATURES_STATE_ID;
import static com.hedera.node.app.history.schemas.V059HistorySchema.LEDGER_ID_STATE_ID;
import static com.hedera.node.app.history.schemas.V059HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V059HistorySchema.PROOF_KEY_SETS_STATE_ID;
import static com.hedera.node.app.history.schemas.V059HistorySchema.PROOF_VOTES_STATE_ID;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.BOOTSTRAP;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.HANDOFF;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.roster.RosterUtils.isWeightRotation;

import com.hedera.hapi.node.state.history.ConstructionNodeId;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.ProofKeySet;
import com.hedera.hapi.node.state.history.RecordedHistorySignature;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of {@link WritableHistoryStore}.
 */
public class WritableHistoryStoreImpl extends ReadableHistoryStoreImpl implements WritableHistoryStore {

    private static final Logger logger = LogManager.getLogger(WritableHistoryStoreImpl.class);

    private final WritableSingletonState<ProtoBytes> ledgerId;
    private final WritableSingletonState<HistoryProofConstruction> nextConstruction;
    private final WritableSingletonState<HistoryProofConstruction> activeConstruction;
    private final WritableKVState<NodeId, ProofKeySet> proofKeySets;
    private final WritableKVState<ConstructionNodeId, RecordedHistorySignature> signatures;
    private final WritableKVState<ConstructionNodeId, HistoryProofVote> votes;

    public WritableHistoryStoreImpl(@NonNull final WritableStates states) {
        super(states);
        this.ledgerId = states.getSingleton(LEDGER_ID_STATE_ID);
        this.nextConstruction = states.getSingleton(NEXT_PROOF_CONSTRUCTION_STATE_ID);
        this.activeConstruction = states.getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID);
        this.proofKeySets = states.get(PROOF_KEY_SETS_STATE_ID);
        this.signatures = states.get(HISTORY_SIGNATURES_STATE_ID);
        this.votes = states.get(PROOF_VOTES_STATE_ID);
    }

    @Override
    public @NonNull HistoryProofConstruction getOrCreateConstruction(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final Instant now,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(activeRosters);
        requireNonNull(now);
        requireNonNull(tssConfig);
        final var phase = activeRosters.phase();
        if (phase == HANDOFF) {
            throw new IllegalArgumentException("Handoff phase has no construction");
        }
        var construction = getConstructionFor(activeRosters);
        if (construction == null) {
            final var gracePeriod = phase == BOOTSTRAP
                    ? tssConfig.bootstrapProofKeyGracePeriod()
                    : tssConfig.transitionProofKeyGracePeriod();
            construction = updateForNewConstruction(
                    activeRosters.sourceRosterHash(),
                    activeRosters.targetRosterHash(),
                    activeRosters::findRelatedRoster,
                    now,
                    gracePeriod);
        }
        return construction;
    }

    @Override
    public boolean setProofKey(final long nodeId, @NonNull final Bytes proofKey, @NonNull final Instant now) {
        requireNonNull(proofKey);
        requireNonNull(now);
        final var id = new NodeId(nodeId);
        var keySet = proofKeySets.get(id);
        boolean inUse = false;
        if (keySet == null) {
            inUse = true;
            keySet = ProofKeySet.newBuilder()
                    .key(proofKey)
                    .adoptionTime(asTimestamp(now))
                    .build();
        } else {
            keySet = keySet.copyBuilder().nextKey(proofKey).build();
        }
        proofKeySets.put(id, keySet);
        return inUse;
    }

    @Override
    public HistoryProofConstruction setAssemblyTime(final long constructionId, @NonNull final Instant now) {
        requireNonNull(now);
        return updateOrThrow(constructionId, b -> b.assemblyStartTime(asTimestamp(now)));
    }

    @Override
    public void addSignature(final long constructionId, @NonNull final HistorySignaturePublication publication) {
        requireNonNull(publication);
        signatures.put(
                new ConstructionNodeId(constructionId, publication.nodeId()),
                new RecordedHistorySignature(asTimestamp(publication.at()), publication.signature()));
    }

    @Override
    public void addProofVote(final long nodeId, final long constructionId, @NonNull final HistoryProofVote vote) {
        requireNonNull(vote);
        votes.put(new ConstructionNodeId(constructionId, nodeId), vote);
    }

    @Override
    public HistoryProofConstruction completeProof(final long constructionId, @NonNull final HistoryProof proof) {
        requireNonNull(proof);
        return updateOrThrow(constructionId, b -> b.targetProof(proof));
    }

    @Override
    public HistoryProofConstruction failForReason(final long constructionId, @NonNull final String reason) {
        requireNonNull(reason);
        return updateOrThrow(constructionId, b -> b.failureReason(reason));
    }

    @Override
    public void setLedgerId(@NonNull final Bytes bytes) {
        requireNonNull(bytes);
        ledgerId.put(new ProtoBytes(bytes));
    }

    @Override
    public boolean handoff(
            @NonNull final Roster fromRoster, @NonNull final Roster toRoster, @NonNull final Bytes toRosterHash) {
        if (requireNonNull(nextConstruction.get()).targetRosterHash().equals(toRosterHash)) {
            // The next construction is becoming the active one; so purge obsolete votes now
            final var upcomingConstruction = requireNonNull(activeConstruction.get());
            purgeVotesAndSignatures(upcomingConstruction.constructionId(), fromRoster);
            if (fromRoster != toRoster && !isWeightRotation(fromRoster, toRoster)) {
                final var survivingNodeIds = toRoster.rosterEntries().stream()
                        .map(RosterEntry::nodeId)
                        .collect(Collectors.toSet());
                fromRoster.rosterEntries().forEach(entry -> {
                    final long nodeId = entry.nodeId();
                    if (!survivingNodeIds.contains(nodeId)) {
                        proofKeySets.remove(new NodeId(nodeId));
                    }
                });
            }
            // And finally, make the next construction the active one
            activeConstruction.put(nextConstruction.get());
            nextConstruction.put(HistoryProofConstruction.DEFAULT);
            return true;
        }
        return false;
    }

    /**
     * Updates the construction with the given ID using the given spec.
     *
     * @param constructionId the construction ID
     * @param spec the spec
     * @return the updated construction
     */
    private HistoryProofConstruction updateOrThrow(
            final long constructionId, @NonNull final UnaryOperator<HistoryProofConstruction.Builder> spec) {
        HistoryProofConstruction construction;
        if (requireNonNull(construction = activeConstruction.get()).constructionId() == constructionId) {
            activeConstruction.put(
                    construction = spec.apply(construction.copyBuilder()).build());
        } else if (requireNonNull(construction = nextConstruction.get()).constructionId() == constructionId) {
            nextConstruction.put(
                    construction = spec.apply(construction.copyBuilder()).build());
        } else {
            throw new IllegalArgumentException("No construction with id " + constructionId);
        }
        return construction;
    }

    /**
     * Updates the store for a new construction.
     * @param sourceRosterHash the source roster hash
     * @param targetRosterHash the target roster hash
     * @param lookup the roster lookup
     * @param now the current time
     * @param gracePeriod the grace period
     * @return the new construction
     */
    private HistoryProofConstruction updateForNewConstruction(
            @NonNull final Bytes sourceRosterHash,
            @NonNull final Bytes targetRosterHash,
            @NonNull final Function<Bytes, Roster> lookup,
            @NonNull final Instant now,
            @NonNull final Duration gracePeriod) {
        var construction = HistoryProofConstruction.newBuilder()
                .constructionId(newConstructionId())
                .sourceRosterHash(sourceRosterHash)
                .targetRosterHash(targetRosterHash)
                .gracePeriodEndTime(asTimestamp(now.plus(gracePeriod)))
                .build();
        final var activeChoice = requireNonNull(activeConstruction.get());
        if (activeChoice.equals(HistoryProofConstruction.DEFAULT)) {
            activeConstruction.put(construction);
            logNewConstruction(construction, InSlot.ACTIVE, sourceRosterHash, targetRosterHash);
        } else {
            if (!requireNonNull(nextConstruction.get()).equals(HistoryProofConstruction.DEFAULT)) {
                // Before switching to the new construction, purge the existing one's votes and signatures
                final var extantConstruction = requireNonNull(nextConstruction.get());
                final var sourceRoster = requireNonNull(lookup.apply(extantConstruction.sourceRosterHash()));
                purgeVotesAndSignatures(extantConstruction.constructionId(), sourceRoster);
            }
            // Copy over any existing target proof as the new construction's source proof
            if (activeChoice.hasTargetProof() && activeChoice.targetRosterHash().equals(sourceRosterHash)) {
                construction = construction
                        .copyBuilder()
                        .sourceProof(activeChoice.targetProofOrThrow())
                        .build();
            }
            nextConstruction.put(construction);
            logNewConstruction(construction, InSlot.NEXT, sourceRosterHash, targetRosterHash);
        }
        // Rotate any proof keys requested to be used in the next construction
        final var adoptionTime = asTimestamp(now);
        final var targetRoster = requireNonNull(lookup.apply(targetRosterHash));
        targetRoster.rosterEntries().forEach(entry -> {
            final var nodeId = new NodeId(entry.nodeId());
            final var keySet = proofKeySets.get(nodeId);
            if (keySet != null && keySet.nextKey().length() > 0) {
                final var rotatedKeySet = keySet.copyBuilder()
                        .key(keySet.nextKey())
                        .adoptionTime(adoptionTime)
                        .nextKey(Bytes.EMPTY)
                        .build();
                proofKeySets.put(nodeId, rotatedKeySet);
            }
        });
        return construction;
    }

    private enum InSlot {
        ACTIVE,
        NEXT
    }

    private void logNewConstruction(
            @NonNull final HistoryProofConstruction construction,
            @NonNull final InSlot slot,
            @NonNull final Bytes sourceRosterHash,
            @NonNull final Bytes targetRosterHash) {
        logger.info(
                "Created {} construction #{} for rosters (source={}, target={}) {} source proof",
                slot,
                construction.constructionId(),
                sourceRosterHash,
                targetRosterHash,
                construction.hasSourceProof() ? "WITH" : "WITHOUT");
    }

    /**
     * Purges the votes for the given construction relative to the given roster.
     *
     * @param sourceRoster the construction
     */
    private void purgeVotesAndSignatures(final long constructionId, @NonNull final Roster sourceRoster) {
        sourceRoster.rosterEntries().forEach(entry -> {
            final var key = new ConstructionNodeId(constructionId, entry.nodeId());
            votes.remove(key);
            signatures.remove(key);
        });
    }

    /**
     * Returns a new construction ID.
     */
    private long newConstructionId() {
        return Math.max(
                        requireNonNull(activeConstruction.get()).constructionId(),
                        requireNonNull(nextConstruction.get()).constructionId())
                + 1;
    }
}
