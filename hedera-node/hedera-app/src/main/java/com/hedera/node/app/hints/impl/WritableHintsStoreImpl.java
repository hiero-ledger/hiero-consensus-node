// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.HintsService.partySizeForRoster;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.HINTS_KEY_SETS_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.PREPROCESSING_VOTES_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_PUBLICATIONS_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.hedera.node.app.roster.ActiveRosters.Phase.BOOTSTRAP;
import static com.hedera.node.app.roster.ActiveRosters.Phase.HANDOFF;
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
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
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

    public WritableHintsStoreImpl(@NonNull final WritableStates states, final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.hintsKeys = states.get(HINTS_KEY_SETS_STATE_ID);
        this.nextConstruction = states.getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID);
        this.activeConstruction = states.getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID);
        this.votes = states.get(PREPROCESSING_VOTES_STATE_ID);
        this.crsState = states.getSingleton(CRS_STATE_STATE_ID);
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
            final var gracePeriod = phase == BOOTSTRAP
                    ? tssConfig.bootstrapHintsKeyGracePeriod()
                    : tssConfig.transitionHintsKeyGracePeriod();
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
    public boolean setHintsKey(
            final long nodeId,
            final int partyId,
            final int numParties,
            @NonNull final Bytes hintsKey,
            @NonNull final Instant now) {
        final var id = new HintsPartyId(partyId, numParties);
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
        // It is pointless to adopt any incomplete construction
        if (!upcomingConstruction.hasHintsScheme()) {
            if (forceHandoff) {
                log.warn(
                        "Ignoring forced handoff to incomplete construction #{}",
                        upcomingConstruction.constructionId());
            }
            return false;
        }
        final boolean handoffMatches = upcomingConstruction.targetRosterHash().equals(toRosterHash);
        if (!handoffMatches) {
            if (forceHandoff) {
                log.warn(
                        "Forcing handoff to construction #{} with different target roster",
                        upcomingConstruction.constructionId());
            } else {
                throw new IllegalStateException("Cannot handoff to construction #"
                        + upcomingConstruction.constructionId() + " with different target roster (constructed for '"
                        + upcomingConstruction.targetRosterHash() + " but incoming is '" + toRosterHash + "')");
            }
        }
        log.info("Handing off to upcoming construction #{}", upcomingConstruction.constructionId());
        // The next construction is becoming the active one; so purge obsolete votes now
        purgeVotes(requireNonNull(activeConstruction.get()), ignore -> fromRoster);
        // If the previous scheme's party size was different than the new one, purge the hinTS keys;
        // this is likely optional, but seems like a better default behavior than leaving them in state
        maybePurgeHintsKeys(partySizeForRoster(toRoster), fromRoster);
        activeConstruction.put(upcomingConstruction);
        nextConstruction.put(HintsConstruction.DEFAULT);
        return true;
    }

    @Override
    public void setCrsState(@NonNull final CRSState crsState) {
        this.crsState.put(crsState);
    }

    @Override
    public void moveToNextNode(
            @NonNull final OptionalLong nextNodeIdFromRoster, @NonNull final Instant nextContributionTimeEnd) {
        final var crsState = requireNonNull(this.crsState.get());
        final var newCrsState = crsState.copyBuilder()
                .nextContributingNodeId(nextNodeIdFromRoster.isPresent() ? nextNodeIdFromRoster.getAsLong() : null)
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
     * @param now              the current time
     * @param gracePeriod      the grace period
     * @return the new construction
     */
    private HintsConstruction updateForNewConstruction(
            @NonNull final Bytes sourceRosterHash,
            @NonNull final Bytes targetRosterHash,
            @NonNull final Function<Bytes, Roster> lookup,
            @NonNull final Instant now,
            @NonNull final Duration gracePeriod) {
        final var construction = HintsConstruction.newBuilder()
                .constructionId(newConstructionId())
                .sourceRosterHash(sourceRosterHash)
                .targetRosterHash(targetRosterHash)
                .gracePeriodEndTime(asTimestamp(now.plus(gracePeriod)))
                .build();
        if (requireNonNull(activeConstruction.get()).equals(HintsConstruction.DEFAULT)) {
            activeConstruction.put(construction);
        } else {
            if (!requireNonNull(nextConstruction.get()).equals(HintsConstruction.DEFAULT)) {
                // Before replacing the next construction, purge its votes
                purgeVotes(requireNonNull(nextConstruction.get()), lookup);
            }
            nextConstruction.put(construction);
        }
        // Rotate any hint keys requested to be used in the next construction
        final var targetRoster = requireNonNull(lookup.apply(targetRosterHash));
        final int numParties = partySizeForRoster(targetRoster);
        final var adoptionTime = asTimestamp(now);
        for (int partyId = 0; partyId < numParties; partyId++) {
            final var hintsId = new HintsPartyId(partyId, numParties);
            final var keySet = hintsKeys.get(hintsId);
            if (keySet != null && keySet.nextKey().length() > 0) {
                final var rotatedKeySet = keySet.copyBuilder()
                        .key(keySet.nextKey())
                        .adoptionTime(adoptionTime)
                        .nextKey(Bytes.EMPTY)
                        .build();
                hintsKeys.put(hintsId, rotatedKeySet);
            }
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
        final var sourceRoster = requireNonNull(lookup.apply(construction.sourceRosterHash()));
        sourceRoster
                .rosterEntries()
                .forEach(entry -> votes.remove(new PreprocessingVoteId(construction.constructionId(), entry.nodeId())));
    }

    /**
     * Purges any hinTS keys for the given roster if it is not for the given party size.
     *
     * @param m the party size
     */
    private void maybePurgeHintsKeys(final int m, @NonNull final Roster roster) {
        final int n = partySizeForRoster(roster);
        if (n != m) {
            for (int partyId = 0; partyId < n; partyId++) {
                final var hintsId = new HintsPartyId(partyId, n);
                hintsKeys.remove(hintsId);
            }
        }
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
