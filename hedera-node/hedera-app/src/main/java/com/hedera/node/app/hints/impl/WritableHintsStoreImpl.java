// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.HintsService.partySizeForRoster;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.HINTS_KEY_SETS_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.PREPROCESSING_VOTES_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_PUBLICATIONS_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.hedera.node.app.hints.schemas.V079HintsSchema.NEXT_CRS_STATE_ID;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.HANDOFF;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.hints.PreprocessingVoteId;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.service.entityid.WritableEntityIdStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of {@link WritableHintsStore}.
 */
public class WritableHintsStoreImpl extends ReadableHintsStoreImpl implements WritableHintsStore {

    private static final Logger log = LogManager.getLogger(WritableHintsStoreImpl.class);

    private final WritableKVState<HintsPartyId, HintsKeySet> hintsKeys;
    private final WritableSingletonState<HintsConstruction> nextConstruction;
    private final WritableSingletonState<HintsConstruction> activeConstruction;
    private final WritableKVState<PreprocessingVoteId, PreprocessingVote> votes;
    private final WritableKVState<NodeId, CrsPublicationTransactionBody> crsPublications;
    private final WritableSingletonState<CRSState> crsState;
    private final WritableSingletonState<CRSState> nextCrsState;
    private final WritableEntityIdStore entityIdStore;

    public WritableHintsStoreImpl(
            @NonNull final WritableStates states, final WritableEntityIdStore writableEntityIdStore) {
        super(states, writableEntityIdStore);
        this.entityIdStore = requireNonNull(writableEntityIdStore);
        this.hintsKeys = states.get(HINTS_KEY_SETS_STATE_ID);
        this.nextConstruction = states.getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID);
        this.activeConstruction = states.getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID);
        this.votes = states.get(PREPROCESSING_VOTES_STATE_ID);
        this.crsState = states.getSingleton(CRS_STATE_STATE_ID);
        this.nextCrsState = states.getSingleton(NEXT_CRS_STATE_ID);
        this.crsPublications = states.get(CRS_PUBLICATIONS_STATE_ID);
    }

    @NonNull
    @Override
    public HintsConstruction getOrCreateConstruction(
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
            construction = updateForNewConstruction(
                    activeRosters.sourceRosterHash(),
                    activeRosters.targetRosterHash(),
                    activeRosters::findRelatedRoster);
        }
        return construction;
    }

    @Override
    public boolean setHintsKey(
            final long nodeId,
            final int partyId,
            final int numParties,
            @NonNull final Bytes hintsKey,
            @NonNull final Instant now) {
        final var id = new HintsPartyId(partyId, numParties, 0);
        var keySet = hintsKeys.get(id);
        boolean inUse = false;
        if (keySet == null) {
            inUse = true;
            keySet = HintsKeySet.newBuilder()
                    .key(hintsKey)
                    .nodeId(nodeId)
                    .adoptionTime(asTimestamp(now))
                    .build();
        } else {
            keySet = keySet.copyBuilder().nodeId(nodeId).nextKey(hintsKey).build();
        }
        hintsKeys.put(id, keySet);
        return inUse;
    }

    @Override
    public boolean setHintsKey(
            final long nodeId,
            final int partyId,
            final int numParties,
            final long crsId,
            @NonNull final Bytes hintsKey,
            @NonNull final Instant now) {
        if (crsId <= 0) {
            throw new IllegalArgumentException("A published hints key must identify its CRS generation");
        }
        final var id = new HintsPartyId(partyId, numParties, crsId);
        var keySet = hintsKeys.get(id);
        // A party released by a departing node can be reassigned under the same CRS. Its old
        // hints belong to the old owner and must not be attributed to the replacement node.
        final boolean inUse = keySet == null || keySet.nodeId() != nodeId;
        if (inUse) {
            keySet = HintsKeySet.newBuilder()
                    .nodeId(nodeId)
                    .key(requireNonNull(hintsKey))
                    .adoptionTime(asTimestamp(now))
                    .build();
        } else {
            keySet = keySet.copyBuilder().nodeId(nodeId).nextKey(hintsKey).build();
        }
        hintsKeys.put(id, keySet);
        return inUse;
    }

    @Override
    public void addPreprocessingVote(
            final long nodeId, final long constructionId, @NonNull final PreprocessingVote vote) {
        votes.put(new PreprocessingVoteId(constructionId, nodeId), vote);
    }

    @Override
    public HintsConstruction setHintsScheme(
            final long constructionId,
            @NonNull final PreprocessedKeys keys,
            @NonNull final Map<Long, Integer> nodePartyIds,
            @NonNull final Map<Long, Long> nodeWeights) {
        requireNonNull(keys);
        requireNonNull(nodePartyIds);
        return updateOrThrow(
                constructionId, b -> b.hintsScheme(new HintsScheme(keys, asList(nodePartyIds, nodeWeights))));
    }

    @Override
    public HintsConstruction setPreprocessingStartTime(final long constructionId, @NonNull final Instant now) {
        requireNonNull(now);
        return updateOrThrow(constructionId, b -> b.preprocessingStartTime(asTimestamp(now)));
    }

    @Override
    public boolean handoff(
            @NonNull final Roster fromRoster,
            @NonNull final Roster toRoster,
            @NonNull final Bytes toRosterHash,
            final boolean forceHandoff) {
        requireNonNull(fromRoster);
        requireNonNull(toRoster);
        requireNonNull(toRosterHash);
        final var upcomingConstruction = requireNonNull(nextConstruction.get());
        final var upcomingCrs = getCrsStateFor(upcomingConstruction);
        final var currentCrs = getCrsState();
        // A forced handoff may not bypass cryptographic readiness or target binding.
        if (!isReadyToAdopt(toRosterHash)
                || upcomingConstruction.numParties() < partySizeForRoster(toRoster)
                || upcomingConstruction.numParties() < currentCrs.numParties()) {
            log.warn(
                    "Ignoring handoff to construction #{} without a matching completed CRS and scheme",
                    upcomingConstruction.constructionId());
            return false;
        }
        final var outgoingConstruction = requireNonNull(activeConstruction.get());
        log.info("Handing off to upcoming construction #{}", upcomingConstruction.constructionId());
        purgeVotes(upcomingConstruction, ignore -> fromRoster);
        purgeVotes(outgoingConstruction, ignore -> fromRoster);
        if (outgoingConstruction.crsId() != upcomingConstruction.crsId()) {
            purgeHintsKeys(outgoingConstruction.numParties(), outgoingConstruction.crsId());
        }
        if (upcomingCrs.ceremonyId() != currentCrs.ceremonyId()) {
            setCrsState(upcomingCrs);
        }
        nextCrsState.put(CRSState.DEFAULT);
        activeConstruction.put(upcomingConstruction);
        nextConstruction.put(HintsConstruction.DEFAULT);
        return true;
    }

    @Override
    public boolean rebindActiveTargetRosterHash(@NonNull final Bytes expectedOldHash, @NonNull final Bytes newHash) {
        requireNonNull(expectedOldHash);
        requireNonNull(newHash);
        final var active = getActiveConstruction();
        if (newHash.length() == 0
                || !active.targetRosterHash().equals(expectedOldHash)
                || !active.hasHintsScheme()
                || !active.hintsSchemeOrThrow().hasPreprocessedKeys()
                || getCrsStateFor(active).stage() != COMPLETED) {
            return false;
        }
        activeConstruction.put(active.copyBuilder().targetRosterHash(newHash).build());
        return true;
    }

    @Override
    public void setCrsState(@NonNull final CRSState crsState) {
        final var current = this.crsState.get();
        final long highWater = Math.max(
                current == null ? 0 : current.lastUsedCeremonyId(),
                Math.max(crsState.lastUsedCeremonyId(), crsState.ceremonyId()));
        final long constructionHighWater =
                Math.max(current == null ? 0 : current.lastUsedConstructionId(), crsState.lastUsedConstructionId());
        this.crsState.put(crsState.copyBuilder()
                .lastUsedCeremonyId(highWater)
                .lastUsedConstructionId(constructionHighWater)
                .build());
    }

    @Override
    public void setNextCrsState(@NonNull final CRSState crsState) {
        nextCrsState.put(requireNonNull(crsState));
    }

    @Override
    public void abandonNextConstruction() {
        final var abandoned = getNextConstruction();
        final var abandonedCrs = getNextCrsState();
        if (abandoned.equals(HintsConstruction.DEFAULT) && abandonedCrs.equals(CRSState.DEFAULT)) {
            return;
        }
        final var currentCrs = getCrsState();
        setCrsState(currentCrs
                .copyBuilder()
                .lastUsedConstructionId(Math.max(currentCrs.lastUsedConstructionId(), abandoned.constructionId()))
                .lastUsedCeremonyId(Math.max(currentCrs.lastUsedCeremonyId(), abandonedCrs.ceremonyId()))
                .build());
        if (!abandoned.equals(HintsConstruction.DEFAULT)) {
            purgeAllVotes(abandoned.constructionId());
            if (abandoned.crsId() != currentCrs.ceremonyId()) {
                purgeHintsKeys(abandoned.numParties(), abandoned.crsId());
            }
        }
        nextConstruction.put(HintsConstruction.DEFAULT);
        nextCrsState.put(CRSState.DEFAULT);
    }

    @Override
    public void setCrsStateFor(@NonNull final HintsConstruction construction, @NonNull final CRSState crsState) {
        final var bound = getCrsStateFor(construction);
        if (bound.ceremonyId() <= 0
                || bound.ceremonyId() != crsState.ceremonyId()
                || bound.numParties() != crsState.numParties()) {
            throw new IllegalArgumentException(
                    "CRS update does not match construction " + construction.constructionId());
        }
        if (getCrsState().ceremonyId() == bound.ceremonyId()) {
            setCrsState(crsState);
        } else {
            setNextCrsState(crsState);
        }
    }

    @Override
    public long allocateCrsId() {
        final var current = getCrsState();
        final var next = getNextCrsState();
        final long allocated = Math.incrementExact(
                Math.max(current.lastUsedCeremonyId(), Math.max(current.ceremonyId(), next.ceremonyId())));
        setCrsState(current.copyBuilder().lastUsedCeremonyId(allocated).build());
        return allocated;
    }

    @Override
    public HintsConstruction bindConstructionToCrs(final long constructionId, final long crsId, final int numParties) {
        if (crsId <= 0 || numParties <= 0 || numParties > 512 || Integer.bitCount(numParties) != 1) {
            throw new IllegalArgumentException("A construction requires a positive CRS ID and power-of-two capacity");
        }
        return updateOrThrow(constructionId, b -> {
            final var existing = b.build();
            if (existing.hasHintsScheme()
                    || existing.hasPreprocessingStartTime()
                    || (existing.crsId() != 0 && (existing.crsId() != crsId || existing.numParties() != numParties))) {
                throw new IllegalStateException("Cannot rebind a started hints construction");
            }
            return b.crsId(crsId).numParties(numParties);
        });
    }

    @Override
    public HintsConstruction startHintsKeyGracePeriod(final long constructionId, @NonNull final Instant end) {
        return startHintsKeyGracePeriod(constructionId, end, end);
    }

    @Override
    public HintsConstruction startHintsKeyGracePeriod(
            final long constructionId, @NonNull final Instant now, @NonNull final Instant end) {
        return updateOrThrow(constructionId, b -> {
            final var construction = b.build();
            final var crs = getCrsStateFor(construction);
            if (crs.ceremonyId() <= 0 || crs.stage() != COMPLETED || crs.crs().length() == 0) {
                throw new IllegalStateException("Cannot collect hints before the bound CRS completes");
            }
            if (construction.hasGracePeriodEndTime()
                    || construction.hasPreprocessingStartTime()
                    || construction.hasHintsScheme()) {
                return b;
            }
            for (int partyId = 0; partyId < construction.numParties(); partyId++) {
                final var keyId = new HintsPartyId(partyId, construction.numParties(), construction.crsId());
                final var keySet = hintsKeys.get(keyId);
                if (keySet != null && keySet.nextKey().length() > 0) {
                    hintsKeys.put(
                            keyId,
                            keySet.copyBuilder()
                                    .key(keySet.nextKey())
                                    .nextKey(Bytes.EMPTY)
                                    .adoptionTime(asTimestamp(now))
                                    .build());
                }
            }
            return b.gracePeriodEndTime(asTimestamp(end));
        });
    }

    @Override
    public void moveToNextNode(
            @Nullable final Long nextNodeIdFromRoster, @NonNull final Instant nextContributionTimeEnd) {
        final var crsState = requireNonNull(this.crsState.get());
        final var newCrsState = crsState.copyBuilder()
                .nextContributingNodeId(nextNodeIdFromRoster)
                .contributionEndTime(asTimestamp(nextContributionTimeEnd))
                .build();
        setCrsState(newCrsState);
    }

    @Override
    public void addCrsPublication(final long nodeId, @NonNull final CrsPublicationTransactionBody crsPublication) {
        crsPublications.put(new NodeId(nodeId), crsPublication);
    }

    /**
     * Updates the construction with the given ID using the given spec.
     *
     * @param constructionId the construction ID
     * @param spec           the spec
     * @return the updated construction
     */
    private HintsConstruction updateOrThrow(
            final long constructionId, @NonNull final UnaryOperator<HintsConstruction.Builder> spec) {
        HintsConstruction construction;
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
     *
     * @param sourceRosterHash the source roster hash
     * @param targetRosterHash the target roster hash
     * @param lookup           the roster lookup
     * @return the new construction
     */
    private HintsConstruction updateForNewConstruction(
            @NonNull final Bytes sourceRosterHash,
            @NonNull final Bytes targetRosterHash,
            @NonNull final Function<Bytes, Roster> lookup) {
        final var construction = HintsConstruction.newBuilder()
                .constructionId(newConstructionId())
                .sourceRosterHash(sourceRosterHash)
                .targetRosterHash(targetRosterHash)
                .build();
        final var previousActive = requireNonNull(activeConstruction.get());
        if (!previousActive.hasHintsScheme()) {
            if (!previousActive.equals(HintsConstruction.DEFAULT)) {
                purgeVotes(previousActive, lookup);
                purgeHintsKeys(previousActive.numParties(), previousActive.crsId());
            }
            activeConstruction.put(construction);
        } else {
            if (!requireNonNull(nextConstruction.get()).equals(HintsConstruction.DEFAULT)) {
                // Before replacing candidate work, purge data outside the active CRS namespace.
                final var abandoned = requireNonNull(nextConstruction.get());
                purgeAllVotes(abandoned.constructionId());
                if (abandoned.crsId() != getCrsState().ceremonyId()) {
                    purgeHintsKeys(abandoned.numParties(), abandoned.crsId());
                }
            }
            nextConstruction.put(construction);
        }
        return construction;
    }

    /**
     * Purges the votes for the given construction relative to the given roster lookup.
     *
     * @param construction the construction
     * @param lookup       the roster lookup
     */
    private void purgeVotes(
            @NonNull final HintsConstruction construction, @NonNull final Function<Bytes, Roster> lookup) {
        final var sourceRoster = lookup.apply(construction.sourceRosterHash());
        if (sourceRoster == null) {
            purgeAllVotes(construction.constructionId());
            return;
        }
        sourceRoster
                .rosterEntries()
                .forEach(entry -> votes.remove(new PreprocessingVoteId(construction.constructionId(), entry.nodeId())));
    }

    /** Uses the persisted node ID allocation bound when an obsolete source roster has been pruned. */
    private void purgeAllVotes(final long constructionId) {
        final long nextNodeId = entityIdStore.peekAtNextNodeId();
        for (long nodeId = 0; nodeId < nextNodeId; nodeId++) {
            votes.remove(new PreprocessingVoteId(constructionId, nodeId));
        }
    }

    /** Removes keys belonging to an obsolete completed CRS generation. */
    private void purgeHintsKeys(final int numParties, final long crsId) {
        for (int partyId = 0; partyId < numParties; partyId++) {
            hintsKeys.remove(new HintsPartyId(partyId, numParties, crsId));
        }
    }

    /**
     * Returns a new construction ID.
     */
    private long newConstructionId() {
        final var currentCrs = getCrsState();
        final long nextId = Math.incrementExact(Math.max(
                currentCrs.lastUsedConstructionId(),
                Math.max(
                        requireNonNull(activeConstruction.get()).constructionId(),
                        requireNonNull(nextConstruction.get()).constructionId())));
        setCrsState(currentCrs.copyBuilder().lastUsedConstructionId(nextId).build());
        return nextId;
    }

    /**
     * Internal helper to construct a list of weighted node party IDs.
     *
     * @param nodePartyIds the map from node ID to party ID
     * @param nodeWeights the map from node ID to weight
     * @return the list of weighted node party IDs, sorted by node ID
     */
    private List<NodePartyId> asList(
            @NonNull final Map<Long, Integer> nodePartyIds, @NonNull final Map<Long, Long> nodeWeights) {
        return nodePartyIds.entrySet().stream()
                .map(entry -> {
                    final long nodeId = entry.getKey();
                    return new NodePartyId(nodeId, entry.getValue(), nodeWeights.get(nodeId));
                })
                .sorted(Comparator.comparingLong(NodePartyId::nodeId))
                .toList();
    }
}
