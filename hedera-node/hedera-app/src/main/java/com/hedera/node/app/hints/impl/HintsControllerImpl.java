// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.hapi.node.state.hints.CRSStage.GATHERING_CONTRIBUTIONS;
import static com.hedera.hapi.node.state.hints.CRSStage.WAITING_FOR_ADOPTING_FINAL_CRS;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.service.roster.impl.RosterTransitionWeights.moreThanTwoThirdsOfTotal;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingLong;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.CrsContributor;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.ReadableHintsStore.HintsKeyPublication;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
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
    /**
     * The effective tally of resolved preprocessing votes. Every entry's value is the actual
     * {@link PreprocessedKeys} that the corresponding node contributed. Congruent votes are
     * resolved to the referent's keys at insertion time so that downstream code can call
     * getValue() without a null-check or a throw.
     *
     * <p>This invariant is established in the constructor (via {@link #resolveVotes}) and
     * maintained by {@link #addPreprocessingVote}, ensuring the map is safe to read on both the
     * consensus thread and the async preprocessing future.
     */
    private final Map<Long, PreprocessedKeys> votes = new ConcurrentHashMap<>();
    /**
     * Congruent votes whose referent is not yet resolved. Keyed by the waiting node's ID, valued by
     * the referent node ID. When the referent later resolves, all pending entries pointing to it
     * are retroactively resolved into {@link #votes}.
     */
    private final Map<Long, Long> pendingCongruentVotes = new ConcurrentHashMap<>();

    private final NavigableMap<Instant, CompletableFuture<Validation>> validationFutures = new TreeMap<>();
    private final Supplier<Configuration> configurationSupplier;
    private final OnHintsFinished onHintsFinished;
    /**
     * The ongoing construction, updated each time the controller advances the construction in state.
     */
    private HintsConstruction construction;

    private boolean cancelled;

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
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final OnHintsFinished onHintsFinished) {
        this.selfId = selfId;
        this.blsPrivateKey = requireNonNull(blsPrivateKey);
        this.weights = requireNonNull(weights);
        this.numParties = construction.numParties();
        this.executor = requireNonNull(executor);
        this.context = requireNonNull(context);
        this.submissions = requireNonNull(submissions);
        this.library = requireNonNull(library);
        this.construction = requireNonNull(construction);
        this.onHintsFinished = requireNonNull(onHintsFinished);
        final var resolveResult = resolveVotes(votes, hintsStore, construction.constructionId());
        this.votes.putAll(resolveResult.resolved());
        this.pendingCongruentVotes.putAll(resolveResult.pending());
        this.configurationSupplier = requireNonNull(configuration);

        final var crsState = hintsStore.getCrsStateFor(construction);
        // Ensure we are up-to-date on any published hinTS keys we might need for this construction
        if (crsState.stage() == COMPLETED && !construction.hasHintsScheme()) {
            final var cutoffTime = construction.hasPreprocessingStartTime()
                    ? asInstant(construction.preprocessingStartTimeOrThrow())
                    : Instant.MAX;
            publications.forEach(publication -> {
                if (!publication.adoptionTime().isAfter(cutoffTime)) {
                    updateHintsKey(crsState.crs(), publication);
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
        return !cancelled && !construction.hasHintsScheme();
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
        if (cancelled) {
            return;
        }
        if (!construction.hasGracePeriodEndTime()
                && !construction.hasPreprocessingStartTime()
                && !construction.hasHintsScheme()) {
            final var active = hintsStore.getActiveConstruction();
            final var candidate = active != null && active.constructionId() == construction.constructionId()
                    ? active
                    : hintsStore.getNextConstruction();
            if (candidate == null || candidate.constructionId() != construction.constructionId()) {
                return;
            }
            construction = candidate;
        }
        if (hintsStore.getCrsStateFor(construction).stage() != COMPLETED
                || construction.hasHintsScheme()
                || (!construction.hasGracePeriodEndTime() && !construction.hasPreprocessingStartTime())) {
            return;
        }
        if (construction.hasPreprocessingStartTime()) {
            if (isActive && !votes.containsKey(selfId) && preprocessingVoteFuture == null) {
                preprocessingVoteFuture = startPreprocessingVoteFuture(
                        asInstant(construction.preprocessingStartTimeOrThrow()),
                        hintsStore.getCrsStateFor(construction).crs());
            }
        } else {
            final var crs = hintsStore.getCrsStateFor(construction).crs();
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
     * Advances the persisted ceremony bound to this construction. Only local submissions depend on
     * platform status; every deadline and state transition is determined by consensus time.
     */
    @Override
    public void advanceCrsWork(
            @NonNull final Instant now, @NonNull final WritableHintsStore hintsStore, final boolean isActive) {
        final var crs = hintsStore.getCrsStateFor(construction);
        if (cancelled || crs.ceremonyId() == 0 || crs.stage() == COMPLETED) {
            return;
        }
        final var config = configurationSupplier.get().getConfigData(TssConfig.class);
        if (crs.stage() == WAITING_FOR_ADOPTING_FINAL_CRS) {
            if (crs.hasContributionEndTime() && !now.isBefore(asInstant(crs.contributionEndTimeOrThrow()))) {
                final var contributedIds = new HashSet<>(crs.contributedNodeIds());
                final long contributedWeight = crs.contributors().stream()
                        .filter(c -> contributedIds.contains(c.nodeId()))
                        .mapToLong(CrsContributor::weight)
                        .sum();
                final long totalWeight = crs.contributors().stream()
                        .mapToLong(CrsContributor::weight)
                        .sum();
                if (totalWeight > 0 && contributedWeight >= moreThanTwoThirdsOfTotal(totalWeight)) {
                    hintsStore.setCrsStateFor(
                            construction,
                            crs.copyBuilder()
                                    .stage(COMPLETED)
                                    .contributionEndTime((Timestamp) null)
                                    .build());
                    log.info(
                            "Completed CRS ceremony #{} with {} distinct contributors",
                            crs.ceremonyId(),
                            contributedIds.size());
                } else {
                    // A new attempt has its own transcript and tally. Reusing the previous pass's
                    // accumulated weight would count the same contributor more than once.
                    final var first = crs.contributors().stream()
                            .mapToLong(CrsContributor::nodeId)
                            .min();
                    hintsStore.setCrsStateFor(
                            construction,
                            crs.copyBuilder()
                                    .stage(GATHERING_CONTRIBUTIONS)
                                    .crs(crs.initialCrs())
                                    .attempt(crs.attempt() + 1)
                                    .contributedNodeIds(List.of())
                                    .nextContributingNodeId(first.isPresent() ? first.getAsLong() : null)
                                    .contributionEndTime(asTimestamp(now.plus(config.crsUpdateContributionTime())))
                                    .build());
                    crsPublicationFuture = null;
                    crsSubmissionKey = null;
                }
            }
        } else if (!crs.hasNextContributingNodeId()) {
            hintsStore.setCrsStateFor(
                    construction,
                    crs.copyBuilder()
                            .stage(WAITING_FOR_ADOPTING_FINAL_CRS)
                            .contributionEndTime(asTimestamp(now.plus(config.crsFinalizationDelay())))
                            .build());
        } else if (crs.hasContributionEndTime() && !now.isBefore(asInstant(crs.contributionEndTimeOrThrow()))) {
            moveToNextNode(now, hintsStore, crs);
        } else if (isActive && crs.nextContributingNodeIdOrThrow() == selfId) {
            submitUpdatedCrs(crs);
        }
    }

    private void moveToNextNode(
            @NonNull final Instant now, @NonNull final WritableHintsStore store, @NonNull final CRSState crs) {
        final var next = crs.contributors().stream()
                .mapToLong(CrsContributor::nodeId)
                .filter(id -> id > crs.nextContributingNodeIdOrThrow())
                .min();
        final var config = configurationSupplier.get().getConfigData(TssConfig.class);
        store.setCrsStateFor(
                construction,
                crs.copyBuilder()
                        .nextContributingNodeId(next.isPresent() ? next.getAsLong() : null)
                        .contributionEndTime(asTimestamp(now.plus(config.crsUpdateContributionTime())))
                        .build());
    }

    private record CrsSubmissionKey(long ceremonyId, long attempt, Bytes headHash) {}

    @Nullable
    private CrsSubmissionKey crsSubmissionKey;

    private void submitUpdatedCrs(@NonNull final CRSState crs) {
        final var key = new CrsSubmissionKey(crs.ceremonyId(), crs.attempt(), noThrowSha384HashOf(crs.crs()));
        if (key.equals(crsSubmissionKey)
                && crsPublicationFuture != null
                && !crsPublicationFuture.isCompletedExceptionally()) {
            return;
        }
        // Capture immutable consensus state before dispatching. The async operation must never read
        // the mutable store, which may already represent a replacement construction when it runs.
        crsSubmissionKey = key;
        crsPublicationFuture = CompletableFuture.supplyAsync(
                        () -> {
                            final var output = requireNonNull(
                                    library.updateCrs(crs.crs(), generateEntropy()), "CRS contribution failed");
                            return decodeCrsUpdate(crs.crs().length(), output);
                        },
                        executor)
                .thenCompose(output -> submissions.submitCrsUpdate(
                        key.ceremonyId(), key.attempt(), key.headHash(), output.crs(), output.proof()));
        crsPublicationFuture.exceptionally(t -> {
            log.warn("Failed to submit contribution for CRS ceremony #{}; will retry", crs.ceremonyId(), t);
            return null;
        });
    }

    /**
     * Generates secure 256-bit entropy.
     *
     * @return the generated entropy
     */
    private Bytes generateEntropy() {
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
        if (cancelled || !construction.hasGracePeriodEndTime()) {
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
        if (cancelled
                || !construction.hasPreprocessingStartTime()
                || hintsStore.getCrsStateFor(construction).stage() != COMPLETED) {
            return false;
        }
        if (votes.containsKey(nodeId)) {
            log.info(
                    "Skipping already-counted preprocessing vote from node{} for construction #{}",
                    nodeId,
                    construction.constructionId());
            return false;
        }
        if (construction.hasHintsScheme()) {
            final var schemeSummary = construction.hintsSchemeOrThrow().hasPreprocessedKeys()
                    ? summarizePreprocessedKeys(
                            construction.hintsSchemeOrThrow().preprocessedKeysOrThrow())
                    : "complete";
            log.info(
                    "Skipping preprocessing vote from node{} for construction #{} because the hinTS scheme is already {}",
                    nodeId,
                    construction.constructionId(),
                    schemeSummary);
            return false;
        }
        hintsStore.addPreprocessingVote(nodeId, constructionId(), vote);
        pendingCongruentVotes.remove(nodeId);
        PreprocessedKeys countedKeys = null;
        if (vote.hasPreprocessedKeys()) {
            countedKeys = vote.preprocessedKeysOrThrow();
            resolveVoteAndDependents(nodeId, countedKeys);
        } else if (vote.hasCongruentNodeId()) {
            final var congruentKeys = votes.get(vote.congruentNodeIdOrThrow());
            if (congruentKeys != null) {
                countedKeys = congruentKeys;
                resolveVoteAndDependents(nodeId, congruentKeys);
            } else {
                pendingCongruentVotes.put(nodeId, vote.congruentNodeIdOrThrow());
            }
        }
        log.info(
                "Accepted preprocessing vote from node{} for construction #{}: {}",
                nodeId,
                construction.constructionId(),
                summarizeVote(vote, countedKeys));
        final var outputWeights = votes.entrySet().stream()
                .collect(groupingBy(Map.Entry::getValue, summingLong(entry -> weights.sourceWeightOf(entry.getKey()))));
        log.info(
                "Now have preprocessing votes with weights {} for construction #{}",
                summarizeOutputWeights(outputWeights),
                construction.constructionId());
        final var maybeWinningOutputs = outputWeights.entrySet().stream()
                .filter(entry -> entry.getValue() >= weights.sourceWeightThreshold())
                .map(Map.Entry::getKey)
                .findFirst();
        maybeWinningOutputs.ifPresent(keys -> {
            construction = hintsStore.setHintsScheme(
                    construction.constructionId(), keys, nodePartyIds, weights.targetNodeWeights());
            log.info(
                    "Completed hinTS scheme for construction #{} with {}",
                    construction.constructionId(),
                    summarizePreprocessedKeys(keys));
            onHintsFinished.accept(hintsStore, construction, context);
        });
        return true;
    }

    /**
     * Adds a resolved vote to the effective tally, then transitively resolves every pending vote
     * that directly or indirectly refers to it.
     *
     * @param nodeId the node whose vote has resolved
     * @param keys the keys selected by the resolved vote
     */
    private void resolveVoteAndDependents(final long nodeId, @NonNull final PreprocessedKeys keys) {
        final var newlyResolved = new ArrayDeque<Long>();
        votes.put(nodeId, keys);
        newlyResolved.add(nodeId);
        while (!newlyResolved.isEmpty()) {
            final long referentNodeId = newlyResolved.removeFirst();
            final var directDependents = pendingCongruentVotes.entrySet().stream()
                    .filter(entry -> entry.getValue() == referentNodeId)
                    .map(Map.Entry::getKey)
                    .toList();
            directDependents.forEach(dependentNodeId -> {
                if (pendingCongruentVotes.remove(dependentNodeId, referentNodeId)
                        && votes.putIfAbsent(dependentNodeId, keys) == null) {
                    newlyResolved.add(dependentNodeId);
                }
            });
        }
    }

    @Override
    public void cancelPendingWork() {
        cancelled = true;
        if (publicationFuture != null) {
            publicationFuture.cancel(true);
        }
        if (preprocessingVoteFuture != null) {
            preprocessingVoteFuture.cancel(true);
        }
        if (crsPublicationFuture != null) {
            crsPublicationFuture.cancel(true);
        }
        validationFutures.values().forEach(future -> future.cancel(true));
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
        final var crs = hintsStore.getCrsStateFor(construction);
        if (cancelled
                || crs.ceremonyId() == 0
                || crs.stage() != GATHERING_CONTRIBUTIONS
                || !crs.hasNextContributingNodeId()
                || crs.nextContributingNodeIdOrThrow() != creatorId
                || crs.ceremonyId() != publication.ceremonyId()
                || crs.attempt() != publication.attempt()
                || !noThrowSha384HashOf(crs.crs()).equals(publication.previousCrsHash())
                || crs.contributedNodeIds().contains(creatorId)
                || (crs.hasContributionEndTime()
                        && !consensusTime.isBefore(asInstant(crs.contributionEndTimeOrThrow())))) {
            return;
        }
        // Validation is pure and completed before the consensus write. Persist the validated head
        // and distinct contributors together, so replay needs no node-local future or transcript KV.
        try {
            if (publication.newCrs().length() != crs.crs().length()
                    || !library.verifyCrsUpdate(crs.crs(), publication.newCrs(), publication.proof())) {
                return;
            }
        } catch (RuntimeException e) {
            log.warn("Ignoring invalid contribution to CRS ceremony #{} from node{}", crs.ceremonyId(), creatorId, e);
            return;
        }
        final var contributors = new ArrayList<>(crs.contributedNodeIds());
        contributors.add(creatorId);
        final var updated = crs.copyBuilder()
                .crs(publication.newCrs())
                .contributedNodeIds(contributors)
                .build();
        moveToNextNode(consensusTime, hintsStore, updated);
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
        if (publication.partyId() == expectedPartyId(publication.nodeId())) {
            updateHintsKey(crs, publication);
        }
    }

    /**
     * Updates the hinTS key for the given node and party id. This includes updating the node and party id
     * mappings and starting a validation future for the hinTS key.
     * @param crs the CRS
     * @param publication the publication
     */
    private void updateHintsKey(@NonNull final Bytes crs, @NonNull final HintsKeyPublication publication) {
        final int partyId = publication.partyId();
        final long nodeId = publication.nodeId();
        nodePartyIds.put(nodeId, partyId);
        partyNodeIds.put(partyId, nodeId);
        validationFutures.put(publication.adoptionTime(), validationFuture(crs, partyId, publication.hintsKey()));
        log.info(
                "Updated hinTS key for node #{} (weight={} of target threshold={})",
                nodeId,
                weights.targetWeightOf(nodeId),
                weights.targetWeightThreshold());
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
     * And no matter which node publishes their key next, they still get the same id as before.
     *
     * @throws IndexOutOfBoundsException if the node id has already been assigned a party id
     */
    private int expectedPartyId(final long nodeId) {
        final var unassignedNodeIds = weights.targetNodeWeights().keySet().stream()
                .filter(id -> !nodePartyIds.containsKey(id))
                .sorted()
                .toList();
        final var unusedPartyIds = IntStream.range(1, numParties)
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
                                    .submitHintsKey(
                                            construction.constructionId(),
                                            construction.crsId(),
                                            selfPartyId,
                                            numParties,
                                            hints)
                                    .join();
                        } catch (CancellationException ignore) {
                            // Normal operations may include cancelling ongoing work
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
                        log.info(
                                "Calling preprocess for construction #{} with crsHash={}, hintKeyHashes={}, aggregatedWeights={}, numParties={}",
                                construction.constructionId(),
                                sha384Hex(crs),
                                summarizeHintKeys(hintKeys),
                                aggregatedWeights,
                                numParties);
                        final var output = library.preprocess(crs, hintKeys, aggregatedWeights, numParties);
                        if (output == null) {
                            log.warn(
                                    "Library returned null preprocessing output for construction #{}; skipping vote",
                                    construction.constructionId());
                            return;
                        }
                        final var preprocessedKeys = PreprocessedKeys.newBuilder()
                                .verificationKey(Bytes.wrap(output.verificationKey()))
                                .aggregationKey(Bytes.wrap(output.aggregationKey()))
                                .build();
                        log.info(
                                "Computed preprocessing output for construction #{}: {}",
                                construction.constructionId(),
                                summarizePreprocessedKeys(preprocessedKeys));
                        // Prefer to vote for a congruent node's preprocessed keys if one exists
                        long congruentNodeId = -1;
                        for (final var entry : votes.entrySet()) {
                            if (entry.getValue().equals(preprocessedKeys)) {
                                congruentNodeId = entry.getKey();
                                break;
                            }
                        }
                        if (congruentNodeId != -1) {
                            log.info(
                                    "Voting for congruent node{}'s preprocessed keys for construction #{}: {}",
                                    congruentNodeId,
                                    construction.constructionId(),
                                    summarizePreprocessedKeys(preprocessedKeys));
                            submissions
                                    .submitHintsVote(construction.constructionId(), congruentNodeId)
                                    .join();
                        } else {
                            log.info(
                                    "Voting for own preprocessed keys for construction #{}: {}",
                                    construction.constructionId(),
                                    summarizePreprocessedKeys(preprocessedKeys));
                            submissions
                                    .submitHintsVote(construction.constructionId(), preprocessedKeys)
                                    .join();
                        }
                    } catch (CancellationException ignore) {
                        // Normal operations may include cancelling ongoing work
                    } catch (Exception e) {
                        log.error("Failed to submit preprocessing vote", e);
                    }
                },
                executor);
    }

    /**
     * Bundles the output of {@link #resolveVotes}: the fully resolved tally and any congruent votes
     * whose referent is unresolved (deferred for retroactive resolution on live arrival).
     */
    private record ResolveResult(
            @NonNull Map<Long, PreprocessedKeys> resolved,
            @NonNull Map<Long, Long> pending) {}

    /**
     * Builds the initial resolved tally from raw persisted votes.
     *
     * <p>First loads the complete referent closure for every congruent vote available in the
     * supplied map. It then walks the resulting dependency graph outward from every explicit vote,
     * resolving congruent chains transitively and independently of map iteration order. Congruent
     * votes in a cycle or whose referent is genuinely absent are placed in
     * {@link ResolveResult#pending()} rather than dropped, so that
     * {@link #addPreprocessingVote} can resolve them retroactively if their referent votes live.
     */
    @NonNull
    private static ResolveResult resolveVotes(
            @NonNull final Map<Long, PreprocessingVote> rawVotes,
            @NonNull final ReadableHintsStore hintsStore,
            final long constructionId) {
        final Map<Long, PreprocessingVote> allVotes = new HashMap<>(rawVotes);
        final Set<Long> queriedReferents = new HashSet<>();
        final var referentsToLoad = new ArrayDeque<Long>();
        rawVotes.values().stream()
                .filter(PreprocessingVote::hasCongruentNodeId)
                .map(PreprocessingVote::congruentNodeIdOrThrow)
                .filter(nodeId -> !allVotes.containsKey(nodeId))
                .forEach(referentsToLoad::add);
        while (!referentsToLoad.isEmpty()) {
            final long referentNodeId = referentsToLoad.removeFirst();
            if (allVotes.containsKey(referentNodeId) || !queriedReferents.add(referentNodeId)) {
                continue;
            }
            final var referentVote =
                    hintsStore.getVotes(constructionId, Set.of(referentNodeId)).get(referentNodeId);
            if (referentVote != null) {
                allVotes.put(referentNodeId, referentVote);
                if (referentVote.hasCongruentNodeId() && !allVotes.containsKey(referentVote.congruentNodeIdOrThrow())) {
                    referentsToLoad.add(referentVote.congruentNodeIdOrThrow());
                }
            }
        }

        final Map<Long, PreprocessedKeys> resolved = new HashMap<>();
        final Map<Long, Long> congruentReferents = new HashMap<>();
        final Map<Long, List<Long>> dependentsByReferent = new HashMap<>();
        final var newlyResolved = new ArrayDeque<Long>();
        allVotes.forEach((nodeId, vote) -> {
            if (vote.hasPreprocessedKeys()) {
                resolved.put(nodeId, vote.preprocessedKeysOrThrow());
                newlyResolved.add(nodeId);
            } else if (vote.hasCongruentNodeId()) {
                final long referentNodeId = vote.congruentNodeIdOrThrow();
                congruentReferents.put(nodeId, referentNodeId);
                dependentsByReferent
                        .computeIfAbsent(referentNodeId, ignored -> new ArrayList<>())
                        .add(nodeId);
            }
        });
        while (!newlyResolved.isEmpty()) {
            final long referentNodeId = newlyResolved.removeFirst();
            final var keys = resolved.get(referentNodeId);
            for (final var dependentNodeId : dependentsByReferent.getOrDefault(referentNodeId, List.of())) {
                if (resolved.putIfAbsent(dependentNodeId, keys) == null) {
                    newlyResolved.add(dependentNodeId);
                }
            }
        }

        final Map<Long, Long> pending = new HashMap<>();
        congruentReferents.forEach((nodeId, referentNodeId) -> {
            if (!resolved.containsKey(nodeId)) {
                pending.put(nodeId, referentNodeId);
                log.warn(
                        "Deferring unresolvable congruent vote from node{} for construction #{}",
                        nodeId,
                        constructionId);
            }
        });
        return new ResolveResult(resolved, pending);
    }

    private static @NonNull String summarizeVote(
            @NonNull final PreprocessingVote vote, @Nullable final PreprocessedKeys countedKeys) {
        requireNonNull(vote);
        if (vote.hasPreprocessedKeys()) {
            return "preprocessedKeys{" + summarizePreprocessedKeys(vote.preprocessedKeysOrThrow()) + "}";
        }
        if (vote.hasCongruentNodeId()) {
            final var summary = "congruentNodeId=" + vote.congruentNodeIdOrThrow();
            return countedKeys == null
                    ? summary + " (not yet resolved)"
                    : summary + " -> preprocessedKeys{" + summarizePreprocessedKeys(countedKeys) + "}";
        }
        return "<empty vote>";
    }

    private static @NonNull List<String> summarizeOutputWeights(
            @NonNull final Map<PreprocessedKeys, Long> outputWeights) {
        requireNonNull(outputWeights);
        return outputWeights.entrySet().stream()
                .map(entry -> "preprocessedKeys{" + summarizePreprocessedKeys(entry.getKey()) + "}, weight="
                        + entry.getValue())
                .sorted()
                .toList();
    }

    private static @NonNull Map<Integer, String> summarizeHintKeys(@NonNull final Map<Integer, Bytes> hintKeys) {
        requireNonNull(hintKeys);
        final Map<Integer, String> summary = new TreeMap<>();
        hintKeys.forEach((partyId, hintsKey) -> summary.put(partyId, sha384Hex(hintsKey)));
        return summary;
    }

    private static @NonNull String summarizePreprocessedKeys(@NonNull final PreprocessedKeys keys) {
        requireNonNull(keys);
        return "vkHash=" + sha384Hex(keys.verificationKey()) + ", akHash=" + sha384Hex(keys.aggregationKey());
    }

    private static @NonNull String sha384Hex(@NonNull final Bytes bytes) {
        requireNonNull(bytes);
        return noThrowSha384HashOf(bytes).toHex();
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
    public record CrsUpdateOutput(
            @NonNull Bytes crs, @NonNull Bytes proof) {}
}
