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
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.HintsService.partySizeForRosterNodeCount;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingLong;
import static java.util.stream.Collectors.toMap;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.ReadableHintsStore.HintsKeyPublication;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.roster.RosterTransitionWeights;
import com.hedera.node.app.tss.TssKeyPair;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Manages the process objects and work needed to advance toward completion of a hinTS construction.
 */
public class HintsControllerImpl implements HintsController {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final int numParties;
    private final long selfId;
    private final Executor executor;
    private final TssKeyPair blsKeyPair;
    private final HintsLibrary library;
    private final HintsLibraryCodec codec;
    private final HintsSubmissions submissions;
    private final HintsContext context;
    private final Map<Long, Integer> nodePartyIds = new HashMap<>();
    private final Map<Integer, Long> partyNodeIds = new HashMap<>();
    private final RosterTransitionWeights weights;
    private final Map<Long, PreprocessingVote> votes = new ConcurrentHashMap<>();
    private final NavigableMap<Instant, CompletableFuture<Validation>> validationFutures = new TreeMap<>();
    private final Supplier<Configuration> configurationSupplier;
    /**
     * The future that resolves to the final updated CRS for the network.
     * This will be null until the first node has contributed to the CRS update.
     */
    @Nullable
    private CompletableFuture<Bytes> finalUpdatedCrsFuture;
    /**
     * The initial CRS for the network. This is used to verify the first update to the CRS. This will be null if
     * the CRS construction is complete when the controller is created.
     */
    @Nullable
    private final Bytes initialCrs;

    /**
     * The ongoing construction, updated each time the controller advances the construction in state.
     */
    private HintsConstruction construction;

    /**
     * If not null, a future that resolves when this node completes the preprocessing stage of this construction.
     */
    @Nullable
    private CompletableFuture<Void> preprocessingVoteFuture;

    /**
     * If not null, the future performing the hinTS key publication for this node.
     */
    @Nullable
    private CompletableFuture<Void> publicationFuture;
    /**
     * If not null, the future performing the CRS update publication for this node.
     */
    @Nullable
    private CompletableFuture<Void> crsPublicationFuture;

    /**
     * A party's validated hinTS key, including the key itself and whether it is valid.
     *
     * @param partyId  the party ID
     * @param hintsKey the hinTS key
     * @param isValid  whether the key is valid
     */
    private record Validation(int partyId, @NonNull Bytes hintsKey, boolean isValid) {}

    public HintsControllerImpl(
            final long selfId,
            @NonNull final TssKeyPair blsKeyPair,
            @NonNull final HintsConstruction construction,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Executor executor,
            @NonNull final HintsLibrary library,
            @NonNull final HintsLibraryCodec codec,
            @NonNull final Map<Long, PreprocessingVote> votes,
            @NonNull final List<HintsKeyPublication> publications,
            @NonNull final HintsSubmissions submissions,
            @NonNull final HintsContext context,
            @NonNull final Supplier<Configuration> configuration,
            @NonNull final WritableHintsStore hintsStore) {
        this.selfId = selfId;
        this.blsKeyPair = requireNonNull(blsKeyPair);
        this.weights = requireNonNull(weights);
        this.numParties = partySizeForRosterNodeCount(weights.targetRosterSize());
        this.executor = requireNonNull(executor);
        this.context = requireNonNull(context);
        this.submissions = requireNonNull(submissions);
        this.library = requireNonNull(library);
        this.codec = requireNonNull(codec);
        this.construction = requireNonNull(construction);
        this.votes.putAll(votes);
        this.configurationSupplier = requireNonNull(configuration);

        final var crsState = hintsStore.getCrsState();
        final var crsPublications = hintsStore.getCrsPublications();
        if (crsState.stage() == CRSStage.GATHERING_CONTRIBUTIONS) {
            crsPublications.forEach(publication -> verifyCrsUpdate(publication, hintsStore));
        }
        this.initialCrs = crsState.stage() != CRSStage.COMPLETED ? crsState.crs() : null;
        // Ensure we are up-to-date on any published hinTS keys we might need for this construction
        if (!construction.hasHintsScheme()) {
            final var cutoffTime = construction.hasPreprocessingStartTime()
                    ? asInstant(construction.preprocessingStartTimeOrThrow())
                    : Instant.MAX;
            publications.forEach(publication -> {
                if (!publication.adoptionTime().isAfter(cutoffTime)) {
                    maybeUpdateForHintsKey(publication);
                }
            });
        }
    }

    @Override
    public long constructionId() {
        return construction.constructionId();
    }

    @Override
    public boolean isStillInProgress() {
        return !construction.hasHintsScheme();
    }

    @Override
    public boolean hasNumParties(final int numParties) {
        return this.numParties == numParties;
    }

    @Override
    public void advanceConstruction(@NonNull final Instant now, @NonNull final WritableHintsStore hintsStore) {
        requireNonNull(now);
        requireNonNull(hintsStore);
        // Do the work needed to set the CRS for network and start the preprocessing vote
        if (hintsStore.getCrsState().stage() != CRSStage.COMPLETED) {
            doCRSWork(now, hintsStore);
        }

        if (construction.hasHintsScheme()) {
            return;
        }
        if (construction.hasPreprocessingStartTime()) {
            if (!votes.containsKey(selfId) && preprocessingVoteFuture == null) {
                preprocessingVoteFuture =
                        startPreprocessingVoteFuture(asInstant(construction.preprocessingStartTimeOrThrow()));
            }
        } else {
            if (shouldStartPreprocessing(now)) {
                construction = hintsStore.setPreprocessingStartTime(construction.constructionId(), now);
                preprocessingVoteFuture = startPreprocessingVoteFuture(now);
            } else {
                ensureHintsKeyPublished();
            }
        }
    }

    /**
     * Performs the work needed to advance the CRS process. This includes:
     * * <ul>
     * <li>If all nodes have contributed, do nothing. Move to the next stage of collecting Hints Keys </li>
     * <li>If there is no initial CRS for the network and if the current node has not submitted one yet,
     * generate one and submit it</li>
     * <li>If the current node is next in line to contribute for updating CRS based on old CRS, generate
     * an updated CRS and submit it</li>
     * </ul>
     *
     * @param now        the current consensus time
     * @param hintsStore the writable hints store
     */
    private void doCRSWork(@NonNull final Instant now, @NonNull final WritableHintsStore hintsStore) {
        final var crsState = hintsStore.getCrsState();
        // If all nodes have contributed
        if (!crsState.hasNextContributingNodeId()) {
            if (crsState.stage() == CRSStage.GATHERING_CONTRIBUTIONS) {
                final var delay = configurationSupplier
                        .get()
                        .getConfigData(TssConfig.class)
                        .crsFinalizationDelay();
                final var updatedState = crsState.copyBuilder()
                        .stage(CRSStage.WAITING_FOR_ADOPTING_FINAL_CRS)
                        .contributionEndTime(asTimestamp(now.plus(delay)))
                        .build();
                hintsStore.setCRSState(updatedState);
            } else if (now.isAfter(asInstant(crsState.contributionEndTimeOrThrow()))) {
                final var finalCrs = requireNonNull(finalUpdatedCrsFuture).join();
                final var updatedState = crsState.copyBuilder()
                        .crs(finalCrs)
                        .stage(CRSStage.COMPLETED)
                        .contributionEndTime((Timestamp) null)
                        .build();
                hintsStore.setCRSState(updatedState);
            }
        } else if (crsState.nextContributingNodeIdOrThrow() == selfId && crsPublicationFuture == null) {
            submitUpdatedCRS(hintsStore);
        } else if (crsState.contributionEndTime() != null
                && now.isAfter(asInstant(crsState.contributionEndTimeOrThrow()))) {
            moveToNextNode(now, hintsStore);
        }
    }

    /**
     * Moves to the next node in the roster to contribute to the CRS. If the current node is the last
     * sets the next contributing node to -1 and sets the contribution end time.
     *
     * @param now        the current consensus time
     * @param hintsStore the writable hints store
     */
    private void moveToNextNode(final @NonNull Instant now, final @NonNull WritableHintsStore hintsStore) {
        final var crsState = hintsStore.getCrsState();
        final var tssConfig = configurationSupplier.get().getConfigData(TssConfig.class);
        final var optionalNextNodeId = nextNodeId(weights.sourceNodeIds(), crsState);

        hintsStore.moveToNextNode(
                optionalNextNodeId,
                now.plusSeconds(tssConfig.crsUpdateContributionTime().toSeconds()));
    }

    /**
     * Submits the updated CRS to the network. This is done asynchronously. The updated CRS is generated
     * by the library by updating the old CRS with new entropy.
     *
     * @param hintsStore the writable hints store
     */
    private void submitUpdatedCRS(final @NonNull WritableHintsStore hintsStore) {
        final var oldCRS = hintsStore.getCrsState().crs();
        crsPublicationFuture = CompletableFuture.runAsync(
                () -> {
                    final var updatedCRS = library.updateCrs(oldCRS, generateEntropy());
                    final var newCRS = codec.decodeCrsUpdate(updatedCRS);
                    submissions.submitUpdateCRS(newCRS.crs(), newCRS.proof());
                },
                executor);
    }

    /**
     * Returns the immediate next node id from the roster after the current node id.
     *
     * @param nodeIds       the node ids in the roster
     * @param crsState      the current CRS state
     * @return the immediate next node id from the roster after the current node id
     */
    private OptionalLong nextNodeId(final Set<Long> nodeIds, final CRSState crsState) {
        if (!crsState.hasNextContributingNodeId()) {
            return OptionalLong.empty();
        }
        return nodeIds.stream()
                .mapToLong(Long::longValue)
                .filter(nodeId -> nodeId > crsState.nextContributingNodeIdOrThrow())
                .findFirst();
    }

    /**
     * Generates secure 128-bit entropy.
     */
    public Bytes generateEntropy() {
        byte[] entropyBytes = new byte[16];
        SECURE_RANDOM.nextBytes(entropyBytes);
        return Bytes.wrap(entropyBytes);
    }

    @Override
    public @NonNull OptionalInt partyIdOf(final long nodeId) {
        if (!weights.targetIncludes(nodeId)) {
            return OptionalInt.empty();
        }
        return nodePartyIds.containsKey(nodeId)
                ? OptionalInt.of(nodePartyIds.get(nodeId))
                : OptionalInt.of(expectedPartyId(nodeId));
    }

    @Override
    public void addHintsKeyPublication(@NonNull final HintsKeyPublication publication) {
        requireNonNull(publication);
        // If grace period is over, we have either finished construction or already set the
        // preprocessing time to something earlier than consensus now; so we will not use
        // this key and can return immediately
        if (!construction.hasGracePeriodEndTime()) {
            return;
        }
        maybeUpdateForHintsKey(publication);
    }

    @Override
    public boolean addPreprocessingVote(
            final long nodeId, @NonNull final PreprocessingVote vote, @NonNull final WritableHintsStore hintsStore) {
        requireNonNull(vote);
        requireNonNull(hintsStore);
        if (!construction.hasHintsScheme() && !votes.containsKey(nodeId)) {
            if (vote.hasPreprocessedKeys()) {
                votes.put(nodeId, vote);
            } else if (vote.hasCongruentNodeId()) {
                final var congruentVote = votes.get(vote.congruentNodeIdOrThrow());
                if (congruentVote != null && congruentVote.hasPreprocessedKeys()) {
                    votes.put(nodeId, congruentVote);
                }
            }
            final var outputWeights = votes.entrySet().stream()
                    .collect(groupingBy(
                            entry -> entry.getValue().preprocessedKeysOrThrow(),
                            summingLong(entry -> weights.sourceWeightOf(entry.getKey()))));
            final var maybeWinningOutputs = outputWeights.entrySet().stream()
                    .filter(entry -> entry.getValue() >= weights.sourceWeightThreshold())
                    .map(Map.Entry::getKey)
                    .findFirst();
            maybeWinningOutputs.ifPresent(keys -> {
                construction = hintsStore.setHintsScheme(construction.constructionId(), keys, nodePartyIds);
                // If this just completed the active construction, update the signing context
                if (hintsStore.getActiveConstruction().constructionId() == construction.constructionId()) {
                    context.setConstruction(construction);
                }
            });
            return true;
        }
        return false;
    }

    @Override
    public void cancelPendingWork() {
        if (publicationFuture != null) {
            publicationFuture.cancel(true);
        }
        if (preprocessingVoteFuture != null) {
            preprocessingVoteFuture.cancel(true);
        }
        if (crsPublicationFuture != null) {
            crsPublicationFuture.cancel(true);
        }
        if (finalUpdatedCrsFuture != null) {
            finalUpdatedCrsFuture.cancel(true);
        }
        validationFutures.values().forEach(future -> future.cancel(true));
    }

    @Override
    public void addCrsPublication(
            @NonNull final CrsPublicationTransactionBody publication,
            @NonNull Instant consensusTime,
            @NonNull WritableHintsStore hintsStore) {
        requireNonNull(publication);
        requireNonNull(consensusTime);
        requireNonNull(hintsStore);

        verifyCrsUpdate(publication, hintsStore);
        moveToNextNode(consensusTime, hintsStore);
    }

    @Override
    public void verifyCrsUpdate(
            @NonNull final CrsPublicationTransactionBody publication, @NonNull final WritableHintsStore hintsStore) {
        if (finalUpdatedCrsFuture == null) {
            finalUpdatedCrsFuture = CompletableFuture.supplyAsync(
                    () -> {
                        final var isValid = library.verifyCrsUpdate(
                                requireNonNull(initialCrs), publication.newCrs(), publication.proof());
                        if (isValid) {
                            return publication.newCrs();
                        }
                        return initialCrs;
                    },
                    executor);
        } else {
            finalUpdatedCrsFuture = finalUpdatedCrsFuture.thenApplyAsync(
                    previousCrs -> {
                        final var isValid =
                                library.verifyCrsUpdate(previousCrs, publication.newCrs(), publication.proof());
                        if (isValid) {
                            return publication.newCrs();
                        }
                        return previousCrs;
                    },
                    executor);
        }
    }

    /**
     * Applies a deterministic policy to choose a preprocessing behavior at the given time.
     *
     * @param now the current consensus time
     * @return the choice of preprocessing behavior
     */
    private boolean shouldStartPreprocessing(@NonNull final Instant now) {
        // If every active node in the target roster has published a hinTS key,
        // start preprocessing now; there is nothing else to wait for
        if (validationFutures.size() == weights.numTargetNodesInSource()) {
            return true;
        }
        if (now.isBefore(asInstant(construction.gracePeriodEndTimeOrThrow()))) {
            return false;
        } else {
            return weightOfValidHintsKeysAt(now) >= weights.targetWeightThreshold();
        }
    }

    /**
     * If the publication is for the expected party id, update the node and party id mappings and
     * start a validation future for the hinTS key.
     *
     * @param publication the publication
     */
    private void maybeUpdateForHintsKey(@NonNull final HintsKeyPublication publication) {
        final int partyId = publication.partyId();
        final long nodeId = publication.nodeId();
        if (partyId == expectedPartyId(nodeId)) {
            nodePartyIds.put(nodeId, partyId);
            partyNodeIds.put(partyId, nodeId);
            validationFutures.put(publication.adoptionTime(), validationFuture(partyId, publication.hintsKey()));
        }
    }

    /**
     * Returns the party ID that this node should use in the target roster. These ids are assigned
     * by sorting the unassigned node ids and unused party ids in ascending order, and matching
     * node ids and party ids by their indexes in these lists.
     * <p>
     * For example, suppose there are three nodes with ids {@code 7}, {@code 9}, and {@code 12};
     * and the party size is four (hence party ids are {@code 0}, {@code 1}, {@code 2}, and {@code 3}).
     * Then we can think of two lists,
     * <ul>
     *     <Li>{@code (7, 9, 12)}</Li>
     *     <Li>{@code (0, 1, 2, 3)}</Li>
     * </ul>
     * And do three assignments: {@code 7 -> 0}, {@code 9 -> 1}, and {@code 12 -> 2}.
     * <p>
     * The important thing about this strategy is that it doesn't matter the <b>order</b> in
     * which we do the assignments. For example, if the nodes publish their keys in the order
     * {@code 9}, {@code 7}, {@code 12}, then after assigning {@code 9 -> 1}, the remaining
     * lists will be,
     * <ul>
     *     <Li>{@code (7, 12)}</Li>
     *     <Li>{@code (0, 2, 3)}</Li>
     * </ul>
     * And no matter which node publishes their key next, they still the same id as expected.
     *
     * @throws IndexOutOfBoundsException if the node id has already been assigned a party id
     */
    private int expectedPartyId(final long nodeId) {
        final var unassignedNodeIds = weights.targetNodeWeights().keySet().stream()
                .filter(id -> !nodePartyIds.containsKey(id))
                .sorted()
                .toList();
        final var unusedPartyIds = IntStream.range(0, numParties)
                .filter(id -> !partyNodeIds.containsKey(id))
                .boxed()
                .toList();
        return unusedPartyIds.get(unassignedNodeIds.indexOf(nodeId));
    }

    /**
     * Returns a future that completes to a validation of the given hints key.
     *
     * @param partyId  the party ID
     * @param hintsKey the hints key
     * @return the future
     */
    private CompletableFuture<Validation> validationFuture(final int partyId, @NonNull final Bytes hintsKey) {
        return CompletableFuture.supplyAsync(
                () -> {
                    final var isValid = library.validateHintsKey(hintsKey, partyId, numParties);
                    return new Validation(partyId, hintsKey, isValid);
                },
                executor);
    }

    /**
     * Returns the weight of the nodes in the target roster that have published valid hinTS keys up to the given time.
     * This is blocking because if we are reduced to checking this, we have already exhausted the grace period waiting
     * for hinTS key publications, and all the futures in this map are essentially guaranteed to be complete, meaning
     * the once-per-round check is very cheap to do.
     *
     * @param now the time up to which to consider hinTS keys
     * @return the weight of the nodes with valid hinTS keys
     */
    private long weightOfValidHintsKeysAt(@NonNull final Instant now) {
        return validationFutures.headMap(now, true).values().stream()
                .map(CompletableFuture::join)
                .filter(Validation::isValid)
                .mapToLong(validation -> weights.targetWeightOf(partyNodeIds.get(validation.partyId())))
                .sum();
    }

    /**
     * If this node is part of the target construction and has not yet published (and is not currently publishing) its
     * hinTS key, then starts publishing it.
     */
    private void ensureHintsKeyPublished() {
        if (publicationFuture == null && weights.targetIncludes(selfId) && !nodePartyIds.containsKey(selfId)) {
            final int selfPartyId = expectedPartyId(selfId);
            publicationFuture = CompletableFuture.runAsync(
                    () -> {
                        final var hints = library.computeHints(blsKeyPair.privateKey(), selfPartyId, numParties);
                        final var hintsKey = codec.encodeHintsKey(blsKeyPair.publicKey(), hints);
                        submissions
                                .submitHintsKey(selfPartyId, numParties, hintsKey)
                                .join();
                    },
                    executor);
        }
    }

    /**
     * Returns a future that completes to the aggregated hinTS keys for this construction for
     * all valid published hinTS keys.
     *
     * @return the future
     */
    private CompletableFuture<Void> startPreprocessingVoteFuture(@NonNull final Instant cutoff) {
        return CompletableFuture.runAsync(
                () -> {
                    // IMPORTANT: since we only start this future when we have a preprocessing start
                    // time, there is no risk of CME with handle thread running addKeyPublication()
                    final var hintKeys = validationFutures.headMap(cutoff, true).values().stream()
                            .map(CompletableFuture::join)
                            .filter(Validation::isValid)
                            .collect(toMap(Validation::partyId, Validation::hintsKey));
                    final var aggregatedWeights = nodePartyIds.entrySet().stream()
                            .filter(entry -> hintKeys.containsKey(entry.getValue()))
                            .collect(toMap(Map.Entry::getValue, entry -> weights.targetWeightOf(entry.getKey())));
                    final var output = library.preprocess(hintKeys, aggregatedWeights, numParties);
                    final var preprocessedKeys = codec.decodePreprocessedKeys(output);
                    // Prefer to vote for a congruent node's preprocessed keys if one exists
                    long congruentNodeId = -1;
                    for (final var entry : votes.entrySet()) {
                        if (entry.getValue().preprocessedKeysOrThrow().equals(preprocessedKeys)) {
                            congruentNodeId = entry.getKey();
                            break;
                        }
                    }
                    if (congruentNodeId != -1) {
                        submissions
                                .submitHintsVote(construction.constructionId(), congruentNodeId)
                                .join();
                    } else {
                        submissions
                                .submitHintsVote(construction.constructionId(), preprocessedKeys)
                                .join();
                    }
                },
                executor);
    }

    @VisibleForTesting
    public void setFinalUpdatedCrsFuture(@Nullable final CompletableFuture<Bytes> finalUpdatedCrsFuture) {
        this.finalUpdatedCrsFuture = finalUpdatedCrsFuture;
    }
}
