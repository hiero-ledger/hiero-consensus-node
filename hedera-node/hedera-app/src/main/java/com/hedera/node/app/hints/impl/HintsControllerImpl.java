// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.hapi.node.state.hints.CRSStage.GATHERING_CONTRIBUTIONS;
import static com.hedera.hapi.node.state.hints.CRSStage.WAITING_FOR_ADOPTING_FINAL_CRS;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.HintsService.partySizeForRosterNodeCount;
import static com.hedera.node.app.roster.RosterTransitionWeights.moreThanTwoThirdsOfTotal;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingLong;
import static java.util.stream.Collectors.toMap;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.ReadableHintsStore.HintsKeyPublication;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.roster.RosterTransitionWeights;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages the process objects and work needed to advance toward completion of a hinTS construction.
 */
public class HintsControllerImpl implements HintsController {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Logger log = LogManager.getLogger(HintsControllerImpl.class);

    private final int numParties;
    private final long selfId;
    private final Executor executor;
    private final Bytes blsPrivateKey;
    private final HintsLibrary library;
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
    private CompletableFuture<CRSValidation> finalCrsFuture;

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
     * @param partyId the party ID
     * @param hintsKey the hinTS key
     * @param isValid whether the key is valid
     */
    private record Validation(int partyId, @NonNull Bytes hintsKey, boolean isValid) {}

    public record CRSValidation(@NonNull Bytes crs, long weightContributedSoFar) {}

    public HintsControllerImpl(
            final long selfId,
            @NonNull final Bytes blsPrivateKey,
            @NonNull final HintsConstruction construction,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Executor executor,
            @NonNull final HintsLibrary library,
            @NonNull final Map<Long, PreprocessingVote> votes,
            @NonNull final List<HintsKeyPublication> publications,
            @NonNull final HintsSubmissions submissions,
            @NonNull final HintsContext context,
            @NonNull final Supplier<Configuration> configuration,
            @NonNull final WritableHintsStore hintsStore) {
        this.selfId = selfId;
        this.blsPrivateKey = requireNonNull(blsPrivateKey);
        this.weights = requireNonNull(weights);
        this.numParties = partySizeForRosterNodeCount(weights.targetRosterSize());
        this.executor = requireNonNull(executor);
        this.context = requireNonNull(context);
        this.submissions = requireNonNull(submissions);
        this.library = requireNonNull(library);
        this.construction = requireNonNull(construction);
        this.votes.putAll(votes);
        this.configurationSupplier = requireNonNull(configuration);

        final var crsState = hintsStore.getCrsState();
        if (crsState.stage() == GATHERING_CONTRIBUTIONS) {
            final var crsPublications = hintsStore.getOrderedCrsPublications(weights.sourceNodeIds());
            crsPublications.forEach((nodeId, publication) -> {
                if (publication != null) {
                    verifyCrsUpdate(publication, hintsStore, nodeId);
                }
            });
        }
        // Ensure we are up-to-date on any published hinTS keys we might need for this construction
        if (crsState.stage() == COMPLETED && !construction.hasHintsScheme()) {
            final var cutoffTime = construction.hasPreprocessingStartTime()
                    ? asInstant(construction.preprocessingStartTimeOrThrow())
                    : Instant.MAX;
            publications.forEach(publication -> {
                if (!publication.adoptionTime().isAfter(cutoffTime)) {
                    maybeUpdateForHintsKey(crsState.crs(), publication);
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
    public void advanceConstruction(
            @NonNull final Instant now, @NonNull final WritableHintsStore hintsStore, final boolean isActive) {
        requireNonNull(now);
        requireNonNull(hintsStore);
        if (hintsStore.getCrsState().stage() != COMPLETED || construction.hasHintsScheme()) {
            return;
        }
        if (construction.hasPreprocessingStartTime() && isActive) {
            final var crs = hintsStore.getCrsState().crs();
            if (!votes.containsKey(selfId) && preprocessingVoteFuture == null) {
                preprocessingVoteFuture =
                        startPreprocessingVoteFuture(asInstant(construction.preprocessingStartTimeOrThrow()), crs);
            }
        } else {
            final var crs = hintsStore.getCrsState().crs();
            if (shouldStartPreprocessing(now)) {
                construction = hintsStore.setPreprocessingStartTime(construction.constructionId(), now);
                if (isActive) {
                    preprocessingVoteFuture = startPreprocessingVoteFuture(now, crs);
                }
            } else if (isActive) {
                ensureHintsKeyPublished(crs);
            }
        }
    }

    /**
     * Performs the work needed to advance the CRS process. This includes,
     * <ul>
     *     <li>If all nodes have contributed, do nothing. Move to the next stage of collecting Hints Keys </li>
     *     <li>If there is no initial CRS for the network and if the current node has not submitted one yet,
     *     generate one and submit it. </li>
     *     <li>If the current node is next in line to contribute for updating CRS based on old CRS, generate
     *     an updated CRS and submit it.</li>
     * </ul>
     *
     * @param now the current consensus time
     * @param hintsStore the writable hints store
     * @param isActive whether this node is active in the network
     */
    @Override
    public void advanceCrsWork(
            @NonNull final Instant now, @NonNull final WritableHintsStore hintsStore, final boolean isActive) {
        final var crsState = hintsStore.getCrsState();
        final var tssConfig = configurationSupplier.get().getConfigData(TssConfig.class);
        try {
            if (!crsState.hasNextContributingNodeId()) {
                tryToFinalizeCrs(now, hintsStore, crsState, tssConfig);
            } else if (crsState.hasContributionEndTime()
                    && now.isAfter(asInstant(crsState.contributionEndTimeOrThrow()))) {
                moveToNextNode(now, hintsStore);
            } else if (crsState.nextContributingNodeIdOrThrow() == selfId && crsPublicationFuture == null && isActive) {
                submitUpdatedCRS(hintsStore);
            }
        } catch (Exception e) {
            log.error("Failed to advance CRS work", e);
        }
    }

    /**
     * If all nodes have contributed to the CRS, try to finalize the CRS. If the threshold is not met,
     * repeat the process from the first node. If the threshold is met, wait for the final future to be completed,
     * set the final updated CRS and mark the stage as completed.
     *
     * @param now the current consensus time
     * @param hintsStore the writable hints store
     * @param crsState the current CRS state
     * @param tssConfig the TSS configuration
     */
    private void tryToFinalizeCrs(
            @NonNull final Instant now,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final CRSState crsState,
            @NonNull final TssConfig tssConfig) {
        if (crsState.stage() == GATHERING_CONTRIBUTIONS) {
            final var delay = tssConfig.crsFinalizationDelay();
            final var updatedState = crsState.copyBuilder()
                    .stage(WAITING_FOR_ADOPTING_FINAL_CRS)
                    .contributionEndTime(asTimestamp(now.plus(delay)))
                    .build();
            hintsStore.setCrsState(updatedState);
            log.info("All nodes have contributed to the CRS, waiting for final adoption");
        } else if (now.isAfter(asInstant(crsState.contributionEndTimeOrThrow()))) {
            final var thresholdMet = validateWeightOfContributions();
            if (!thresholdMet) {
                // If the threshold is not met, restart the process
                restartFromFirstNode(now, hintsStore, tssConfig);
            } else {
                final var finalUpdatedCrs = requireNonNull(finalCrsFuture).join();
                final var updatedState = crsState.copyBuilder()
                        .crs(finalUpdatedCrs.crs())
                        .stage(COMPLETED)
                        .contributionEndTime((Timestamp) null)
                        .build();
                hintsStore.setCrsState(updatedState);
                log.info("CRS construction complete");
            }
        }
    }

    /**
     * Starts CRS contribution for the first node in the source roster.
     * This is called when all nodes have contributed to the CRS, but the total weight of all nodes contributing
     * is less than 2/3 of the total weight of all nodes in the source roster.
     *
     * @param now the current consensus time
     * @param hintsStore the writable hints store
     * @param tssConfig the TSS configuration
     */
    private void restartFromFirstNode(
            @NonNull final Instant now,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final TssConfig tssConfig) {
        log.warn("Restarting CRS ceremony from the first node because threshold not met for CRS contributions");
        if (crsPublicationFuture != null && !crsPublicationFuture.isDone()) {
            crsPublicationFuture.cancel(true);
        }
        crsPublicationFuture = null;
        final var crsState = hintsStore.getCrsState();
        final var firstNodeId =
                weights.sourceNodeIds().stream().min(Long::compareTo).orElse(0L);
        final var contributionTime = tssConfig.crsUpdateContributionTime();
        final var updatedState = crsState.copyBuilder()
                .stage(GATHERING_CONTRIBUTIONS)
                .contributionEndTime(asTimestamp(now.plus(contributionTime)))
                .nextContributingNodeId(firstNodeId)
                .build();
        hintsStore.setCrsState(updatedState);
    }

    /**
     * Checks if the total weight of the contributions is more than 2/3 total weight of all nodes in the source
     * roster.
     *
     * @return true if the total weight of the contributions is more than 2/3 total weight of all nodes in the
     */
    private boolean validateWeightOfContributions() {
        final var contributedWeight =
                finalCrsFuture == null ? 0L : finalCrsFuture.join().weightContributedSoFar();
        final var totalWeight = weights.sourceNodeWeights().values().stream()
                .mapToLong(Long::longValue)
                .sum();
        log.info("Total weight of CRS contributions is {} (of {} total)", contributedWeight, totalWeight);
        return contributedWeight >= moreThanTwoThirdsOfTotal(totalWeight);
    }

    /**
     * Moves to the next node in the roster to contribute to the CRS. If the current node is the last
     * sets the next contributing node to -1 and sets the contribution end time.
     *
     * @param now the current consensus time
     * @param hintsStore the writable hints store
     */
    private void moveToNextNode(final @NonNull Instant now, final @NonNull WritableHintsStore hintsStore) {
        final var crsState = hintsStore.getCrsState();
        final var tssConfig = configurationSupplier.get().getConfigData(TssConfig.class);
        final var optionalNextNodeId = nextNodeId(weights.sourceNodeIds(), crsState);
        log.info(
                "{} for CRS contribution",
                optionalNextNodeId.stream()
                        .mapToObj(l -> "Moving on to node" + l)
                        .findFirst()
                        .orElse("No remaining nodes to consider"));
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
        crsPublicationFuture = CompletableFuture.runAsync(
                () -> {
                    try {
                        final var previousCrs = (finalCrsFuture != null)
                                ? finalCrsFuture.join().crs()
                                : hintsStore.getCrsState().crs();
                        final var updatedCrs = library.updateCrs(previousCrs, generateEntropy());
                        final var newCrs = decodeCrsUpdate(previousCrs.length(), updatedCrs);
                        submissions
                                .submitCrsUpdate(newCrs.crs(), newCrs.proof())
                                .join();
                    } catch (Exception e) {
                        log.error("Failed to submit updated CRS", e);
                    }
                },
                executor);
    }

    /**
     * Returns the immediate next node id from the roster after the current node id.
     *
     * @param nodeIds the node ids in the roster
     * @param crsState the current CRS state
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
     * Generates secure 256-bit entropy.
     *
     * @return the generated entropy
     */
    public Bytes generateEntropy() {
        byte[] entropyBytes = new byte[32];
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
    public void addHintsKeyPublication(@NonNull final HintsKeyPublication publication, final Bytes crs) {
        requireNonNull(publication);
        // If grace period is over, we have either finished construction or already set the
        // preprocessing time to something earlier than consensus now; so we will not use
        // this key and can return immediately
        if (!construction.hasGracePeriodEndTime()) {
            log.info("Ignoring tardy hinTS key from node{}", publication.nodeId());
            return;
        }
        maybeUpdateForHintsKey(crs, publication);
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
                log.info("Completed hinTS Scheme for construction #{}", construction.constructionId());
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
        if (finalCrsFuture != null) {
            finalCrsFuture.cancel(true);
        }
        validationFutures.values().forEach(future -> future.cancel(true));
    }

    @Override
    public void addCrsPublication(
            @NonNull final CrsPublicationTransactionBody publication,
            @NonNull Instant consensusTime,
            @NonNull WritableHintsStore hintsStore,
            final long creatorId) {
        requireNonNull(publication);
        requireNonNull(consensusTime);
        requireNonNull(hintsStore);

        verifyCrsUpdate(publication, hintsStore, creatorId);
        moveToNextNode(consensusTime, hintsStore);
    }

    @Override
    public void verifyCrsUpdate(
            @NonNull final CrsPublicationTransactionBody publication,
            @NonNull final ReadableHintsStore hintsStore,
            final long creatorId) {
        requireNonNull(publication);
        requireNonNull(hintsStore);
        final var creatorWeight = weights.sourceWeightOf(creatorId);
        if (finalCrsFuture == null) {
            final var initialCrs = hintsStore.getCrsState().crs();
            finalCrsFuture = CompletableFuture.supplyAsync(
                    () -> {
                        final var isValid =
                                library.verifyCrsUpdate(initialCrs, publication.newCrs(), publication.proof());
                        if (isValid) {
                            return new CRSValidation(publication.newCrs(), creatorWeight);
                        }
                        return new CRSValidation(initialCrs, 0L);
                    },
                    executor);
        } else {
            finalCrsFuture = finalCrsFuture.thenApplyAsync(
                    previousValidation -> {
                        final var isValid = library.verifyCrsUpdate(
                                previousValidation.crs(), publication.newCrs(), publication.proof());
                        if (isValid) {
                            return new CRSValidation(
                                    publication.newCrs(), previousValidation.weightContributedSoFar() + creatorWeight);
                        }
                        return new CRSValidation(previousValidation.crs(), previousValidation.weightContributedSoFar());
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
            log.info("All nodes have published hinTS keys. Starting preprocessing.");
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
     * @param crs the CRS
     * @param publication the publication
     */
    private void maybeUpdateForHintsKey(@NonNull final Bytes crs, @NonNull final HintsKeyPublication publication) {
        requireNonNull(publication);
        requireNonNull(crs);
        final int partyId = publication.partyId();
        final long nodeId = publication.nodeId();
        if (partyId == expectedPartyId(nodeId)) {
            nodePartyIds.put(nodeId, partyId);
            partyNodeIds.put(partyId, nodeId);
            validationFutures.put(publication.adoptionTime(), validationFuture(crs, partyId, publication.hintsKey()));
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
        final var unusedPartyIds = IntStream.range(1, numParties + 1)
                .filter(id -> !partyNodeIds.containsKey(id))
                .boxed()
                .toList();
        return unusedPartyIds.get(unassignedNodeIds.indexOf(nodeId));
    }

    /**
     * Returns a future that completes to a validation of the given hints key.
     *
     * @param crs the initial CRS
     * @param partyId the party ID
     * @param hintsKey the hints key
     * @return the future
     */
    private CompletableFuture<Validation> validationFuture(
            final Bytes crs, final int partyId, @NonNull final Bytes hintsKey) {
        return CompletableFuture.supplyAsync(
                () -> {
                    boolean isValid = false;
                    try {
                        isValid = library.validateHintsKey(crs, hintsKey, partyId, numParties);
                    } catch (Exception e) {
                        log.warn("Failed to validate hints key {} for party{} of {}", hintsKey, partyId, numParties, e);
                    }
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
    private void ensureHintsKeyPublished(final Bytes crs) {
        if (publicationFuture == null && weights.targetIncludes(selfId) && !nodePartyIds.containsKey(selfId)) {
            final int selfPartyId = expectedPartyId(selfId);
            publicationFuture = CompletableFuture.runAsync(
                    () -> {
                        try {
                            final var hints = library.computeHints(crs, blsPrivateKey, selfPartyId, numParties);
                            submissions
                                    .submitHintsKey(selfPartyId, numParties, hints)
                                    .join();
                        } catch (Exception e) {
                            log.error("Failed to publish hinTS key", e);
                        }
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
    private CompletableFuture<Void> startPreprocessingVoteFuture(@NonNull final Instant cutoff, final Bytes crs) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        // IMPORTANT: since we only start this future when we have a preprocessing start
                        // time, there is no risk of CME with handle thread running addKeyPublication()
                        final var hintKeys = validationFutures.headMap(cutoff, true).values().stream()
                                .map(CompletableFuture::join)
                                .filter(Validation::isValid)
                                .collect(toMap(Validation::partyId, Validation::hintsKey, (a, b) -> a, TreeMap::new));
                        final var aggregatedWeights = nodePartyIds.entrySet().stream()
                                .filter(entry -> hintKeys.containsKey(entry.getValue()))
                                .collect(toMap(
                                        Map.Entry::getValue,
                                        entry -> weights.targetWeightOf(entry.getKey()),
                                        (a, b) -> a,
                                        TreeMap::new));
                        final var output = library.preprocess(crs, hintKeys, aggregatedWeights, numParties);
                        final var preprocessedKeys = PreprocessedKeys.newBuilder()
                                .verificationKey(Bytes.wrap(output.verificationKey()))
                                .aggregationKey(Bytes.wrap(output.aggregationKey()))
                                .build();
                        // Prefer to vote for a congruent node's preprocessed keys if one exists
                        long congruentNodeId = -1;
                        for (final var entry : votes.entrySet()) {
                            if (entry.getValue().preprocessedKeysOrThrow().equals(preprocessedKeys)) {
                                congruentNodeId = entry.getKey();
                                break;
                            }
                        }
                        if (congruentNodeId != -1) {
                            log.info("Voting for congruent node's preprocessed keys: {}", congruentNodeId);
                            submissions
                                    .submitHintsVote(construction.constructionId(), congruentNodeId)
                                    .join();
                        } else {
                            log.info("Voting for own preprocessed keys");
                            submissions
                                    .submitHintsVote(construction.constructionId(), preprocessedKeys)
                                    .join();
                        }
                    } catch (Exception e) {
                        log.error("Failed to submit preprocessing vote", e);
                    }
                },
                executor);
    }

    @VisibleForTesting
    public void setFinalCrsFuture(@Nullable final CompletableFuture<CRSValidation> finalCrsFuture) {
        this.finalCrsFuture = finalCrsFuture;
    }

    /**
     * Decodes the output of {@link HintsLibrary#updateCrs(Bytes, Bytes)} into a
     * {@link CrsUpdateOutput}.
     *
     * @param oldCrsLength the length of the old CRS
     * @param output the output of the {@link HintsLibrary#updateCrs(Bytes, Bytes)}
     * @return the hinTS key
     */
    public static CrsUpdateOutput decodeCrsUpdate(final long oldCrsLength, @NonNull final Bytes output) {
        requireNonNull(output);
        final var crs = output.slice(0, oldCrsLength);
        final var proof = output.slice(oldCrsLength, output.length() - oldCrsLength);
        return new CrsUpdateOutput(crs, proof);
    }

    /**
     * A structured representation of the output of {@link HintsLibrary#updateCrs(Bytes, Bytes)}.
     *
     * @param crs the updated CRS
     * @param proof the proof of the update
     */
    public record CrsUpdateOutput(@NonNull Bytes crs, @NonNull Bytes proof) {}
}
