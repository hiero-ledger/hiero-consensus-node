// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.history.HistoryLibrary.MAX_ADDRESS_BOOK_SIZE;
import static com.hedera.node.app.history.HistoryLibrary.MISSING_SCHNORR_KEY;
import static com.hedera.node.app.history.HistoryLibrary.isIdentityKey;
import static com.hedera.node.app.history.HistoryService.isCompleted;
import static com.hedera.node.app.history.impl.ProofControllers.groundsChainOfTrust;
import static com.hedera.node.app.history.impl.ProofControllers.isWrapsExtensible;
import static com.hedera.node.app.history.impl.ProofVoteCategory.INVALID;
import static com.hedera.node.app.history.impl.ProofVoteCategory.VALID;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingLong;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.hapi.node.state.history.WrapsSigningState;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryLibrary.AddressBook;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.ProofKeysAccessorImpl.SchnorrKeyPair;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of {@link ProofController}.
 */
public class ProofControllerImpl implements ProofController {
    private static final Logger log = LogManager.getLogger(ProofControllerImpl.class);
    private static final Comparator<ProofKeyPublication> PUBLICATION_ORDER =
            Comparator.comparing(ProofKeyPublication::adoptionTime).thenComparingLong(ProofKeyPublication::nodeId);

    private final long selfId;

    private final Executor executor;
    private final SchnorrKeyPair schnorrKeyPair;
    private final HistoryLibrary historyLibrary;
    private final HistoryService historyService;
    private final HistorySubmissions submissions;
    private final RosterTransitionWeights weights;
    private final HistoryProver.Factory proverFactory;
    private final HistoryProofMetrics historyProofMetrics;

    /**
     * Current platform members that can publish keys for the target roster, captured independently of
     * the hinTS source authority. Null for callers without platform membership, which conservatively
     * wait for the complete target roster before taking the all-publishers fast path.
     */
    @Nullable
    private Set<Long> expectedProofKeyPublishers;

    @Nullable
    private final HistoryProof sourceProof;

    private final Map<Long, ExplicitProofVote> votes = new TreeMap<>();
    private final Map<Long, Bytes> targetProofKeys = new TreeMap<>();
    private final Map<Long, ProofKeyPublication> targetProofKeyPublications = new TreeMap<>();
    private final Map<ProofValidationKey, Boolean> proofTagValidations = new HashMap<>();

    /**
     * The ongoing construction, updated in network state each time the controller makes progress.
     */
    private HistoryProofConstruction construction;

    /**
     * If not null, a future that resolves when this node publishes its Schnorr key.
     */
    @Nullable
    private CompletableFuture<Void> publicationFuture;

    /**
     * If not null, the prover responsible for the current construction.
     */
    @Nullable
    private HistoryProver prover;

    /**
     * If not null, the prover responsible for the current construction.
     */
    @Nullable
    private Bytes targetMetadata;

    /**
     * If not null, the target history expected from this node's view of the target roster and selected proof keys.
     */
    @Nullable
    private History expectedTargetHistory;

    private static class ExplicitProofVote {
        private final Bytes tag;
        private final HistoryProofVote historyProofVote;

        public ExplicitProofVote(@NonNull final HistoryProofVote historyProofVote) {
            this.historyProofVote = requireNonNull(historyProofVote);
            // Votes are validated (and validations cached) per tag, so the tag must commit to every
            // field of the proof that could be finalized; not just to the WRAPS proof
            tag = noThrowSha384HashOf(HistoryProof.PROTOBUF.toBytes(historyProofVote.proofOrThrow()));
        }

        public Bytes tag() {
            return tag;
        }

        public HistoryProofVote historyProofVote() {
            return historyProofVote;
        }

        public HistoryProof proof() {
            return historyProofVote.proofOrThrow();
        }

        public byte[] compressedProofOrEmpty() {
            return proof().chainOfTrustProofOrElse(ChainOfTrustProof.DEFAULT)
                    .wrapsProofOrElse(Bytes.EMPTY)
                    .toByteArray();
        }
    }

    private record ProofValidationKey(
            @NonNull Bytes proofTag,
            @NonNull Bytes ledgerId,
            @NonNull Bytes metadata) {
        private ProofValidationKey {
            requireNonNull(proofTag);
            requireNonNull(ledgerId);
            requireNonNull(metadata);
        }
    }

    public ProofControllerImpl(
            final long selfId,
            @NonNull final SchnorrKeyPair schnorrKeyPair,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Executor executor,
            @NonNull final HistorySubmissions submissions,
            @NonNull final WrapsMpcStateMachine machine,
            @NonNull final List<ProofKeyPublication> keyPublications,
            @NonNull final List<WrapsMessagePublication> wrapsMessagePublications,
            @NonNull final Map<Long, HistoryProofVote> votes,
            @NonNull final HistoryService historyService,
            @NonNull final HistoryLibrary historyLibrary,
            @NonNull final HistoryProver.Factory proverFactory,
            @Nullable final HistoryProof sourceProof,
            @NonNull final HistoryProofMetrics historyProofMetrics,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(machine);
        requireNonNull(tssConfig);
        this.selfId = selfId;
        this.executor = requireNonNull(executor);
        this.submissions = requireNonNull(submissions);
        this.weights = requireNonNull(weights);
        this.construction = requireNonNull(construction);
        this.proverFactory = requireNonNull(proverFactory);
        this.sourceProof = sourceProof;
        this.historyProofMetrics = requireNonNull(historyProofMetrics);
        this.historyLibrary = requireNonNull(historyLibrary);
        this.historyService = requireNonNull(historyService);
        this.schnorrKeyPair = requireNonNull(schnorrKeyPair);
        this.targetMetadata = construction.targetMetadata();
        replayPersistedVotes(votes);
        if (!isCompleted(construction)) {
            // Collection remains open until assembly actually starts, even if metadata arrived
            // after the grace deadline. The frozen cutoff survives every WRAPS phase and retry.
            if ((construction.hasWrapsSigningState() || construction.hasFailureReason())
                    && !construction.hasProofKeyCutoffTime()) {
                throw new IllegalArgumentException("Legacy WRAPS construction must be replaced before replay");
            }
            final var cutoffTime = construction.hasProofKeyCutoffTime()
                    ? asInstant(construction.proofKeyCutoffTimeOrThrow())
                    : construction.hasAssemblyStartTime()
                            ? asInstant(construction.assemblyStartTimeOrThrow())
                            : Instant.MAX;
            keyPublications.forEach(publication -> {
                if (!publication.adoptionTime().isAfter(cutoffTime)) {
                    maybeUpdateForProofKey(publication);
                }
            });
            this.prover = createProver(tssConfig);
            wrapsMessagePublications.stream().sorted().forEach(publication -> requireNonNull(prover)
                    .replayWrapsSigningMessage(constructionId(), publication));
        }
    }

    /**
     * Creates a controller with the platform members expected to publish keys for the target roster.
     * Joining target nodes are not expected to publish until the platform admits them; current members
     * remain expected even when they did not participate in the source hinTS scheme.
     *
     * @param expectedProofKeyPublishers the current platform/target membership intersection
     */
    public ProofControllerImpl(
            final long selfId,
            @NonNull final SchnorrKeyPair schnorrKeyPair,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Executor executor,
            @NonNull final HistorySubmissions submissions,
            @NonNull final WrapsMpcStateMachine machine,
            @NonNull final List<ProofKeyPublication> keyPublications,
            @NonNull final List<WrapsMessagePublication> wrapsMessagePublications,
            @NonNull final Map<Long, HistoryProofVote> votes,
            @NonNull final HistoryService historyService,
            @NonNull final HistoryLibrary historyLibrary,
            @NonNull final HistoryProver.Factory proverFactory,
            @Nullable final HistoryProof sourceProof,
            @NonNull final HistoryProofMetrics historyProofMetrics,
            @NonNull final TssConfig tssConfig,
            @NonNull final Set<Long> expectedProofKeyPublishers) {
        this(
                selfId,
                schnorrKeyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                votes,
                historyService,
                historyLibrary,
                proverFactory,
                sourceProof,
                historyProofMetrics,
                tssConfig);
        final var expected = Set.copyOf(requireNonNull(expectedProofKeyPublishers));
        if (!weights.targetNodeWeights().keySet().containsAll(expected)) {
            throw new IllegalArgumentException("Expected proof key publishers must belong to the target roster");
        }
        this.expectedProofKeyPublishers = expected;
    }

    @Override
    public long constructionId() {
        return construction.constructionId();
    }

    @Override
    public boolean isStillInProgress() {
        if (construction.hasFailureReason()) {
            return false;
        }
        return !isCompleted(construction);
    }

    @Override
    public void advanceConstruction(
            @NonNull final Instant now,
            @Nullable final Bytes metadata,
            @NonNull final WritableHistoryStore historyStore,
            final boolean isActive,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(now);
        requireNonNull(historyStore);
        requireNonNull(tssConfig);
        targetMetadata = metadata;
        historyProofMetrics.observeStage(constructionId(), currentStage(metadata), now);
        try {
            if (construction.hasFailureReason()) {
                if (!retryIfRecoverableFailure(construction.failureReasonOrThrow(), historyStore, tssConfig)) {
                    return;
                }
            }
            if (!isStillInProgress()) {
                return;
            }
            // Still waiting for the hinTS verification key
            if (metadata == null) {
                if (isActive) {
                    ensureProofKeyPublished();
                }
                return;
            }
            // Have the hinTS verification key, but not yet assembling the history
            // or computing the WRAPS proof (genesis or incremental)
            if (!construction.hasTargetProof()
                    && !construction.hasAssemblyStartTime()
                    && !construction.hasWrapsSigningState()) {
                // Publish a replacement even when every target already has a key and assembly can start.
                // Replacements are queued for a later construction; local key loss must not delay assembly.
                if (isActive) {
                    ensureProofKeyPublished();
                }
                if (shouldAssemble(now)) {
                    log.info("Assembly start time for construction #{} is {}", construction.constructionId(), now);
                    construction = historyStore.setAssemblyTime(construction.constructionId(), now);
                }
                return;
            }
            final var outcome = requireNonNull(prover)
                    .advance(
                            now,
                            construction,
                            metadata,
                            targetProofKeys,
                            tssConfig,
                            historyStore.getLedgerId(),
                            isActive);
            switch (outcome) {
                case HistoryProver.Outcome.InProgress ignored ->
                    construction = historyStore.getConstructionOrThrow(constructionId());
                case HistoryProver.Outcome.Completed completed -> finishProof(historyStore, completed.proof(), now);
                case HistoryProver.Outcome.Failed failed -> {
                    if (!retryIfRecoverableFailure(failed.reason(), historyStore, tssConfig)) {
                        log.warn("Failed construction #{} due to {}", constructionId(), failed.reason());
                        construction = historyStore.failForReason(constructionId(), failed.reason());
                    }
                }
            }
        } finally {
            historyProofMetrics.observeStage(constructionId(), currentStage(metadata), now);
        }
    }

    @Override
    public void addProofKeyPublication(@NonNull final ProofKeyPublication publication) {
        requireNonNull(publication);
        // Keys adopted through the assembly cutoff must be selected both live and after replay.
        if (!construction.hasGracePeriodEndTime()
                && (!construction.hasProofKeyCutoffTime()
                        || publication.adoptionTime().isAfter(asInstant(construction.proofKeyCutoffTimeOrThrow())))) {
            return;
        }
        maybeUpdateForProofKey(publication);
    }

    @Override
    public boolean addWrapsMessagePublication(
            @NonNull final WrapsMessagePublication publication,
            @NonNull final WritableHistoryStore writableHistoryStore) {
        requireNonNull(publication);
        requireNonNull(writableHistoryStore);
        // Assembly can choose the end of the current round as its key cutoff, before that round's
        // transactions finish publishing keys. Signing must wait until those selections are final.
        if (construction.hasTargetProof()
                || (construction.hasProofKeyCutoffTime()
                        && !publication.receiptTime().isAfter(asInstant(construction.proofKeyCutoffTimeOrThrow())))
                || targetMetadata == null
                || (!construction.hasAssemblyStartTime() && !construction.hasWrapsSigningState())) {
            return false;
        }
        return requireNonNull(prover).addWrapsSigningMessage(constructionId(), publication, writableHistoryStore);
    }

    @Override
    public void addProofVote(
            final long nodeId,
            @NonNull final HistoryProofVote vote,
            @NonNull final Instant now,
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(vote);
        requireNonNull(now);
        requireNonNull(historyStore);
        requireNonNull(tssConfig);
        // A controller rebuilt from state loads only the source nodes' votes (see ProofControllers), so a vote from
        // any other node must not count live either; otherwise live and rebuilt nodes could tally and persist
        // different votes, for example congruent votes referencing an explicit vote only the live nodes counted
        if (!weights.sourceNodeIds().contains(nodeId)) {
            return;
        }
        // A precomputed proof cannot finish assembly while keys from its cutoff round can still arrive.
        // This guard applies only to new votes; persisted votes are replayed separately by the constructor.
        if (construction.hasProofKeyCutoffTime() && !now.isAfter(asInstant(construction.proofKeyCutoffTimeOrThrow()))) {
            return;
        }
        if (votes.containsKey(nodeId)) {
            log.info(
                    "Skipping already-counted vote from node{} for construction #{}",
                    nodeId,
                    construction.constructionId());
            return;
        }
        if (!incorporateVote(nodeId, vote)) {
            // Late arrival, proof was already finished
            return;
        }
        final var explicitProofVote = votes.get(nodeId);
        if (explicitProofVote == null) {
            // Malformed vote, nothing to do with it
            return;
        }
        // Put the vote in state to let reconnecting nodes retrace our steps
        historyStore.addProofVote(nodeId, construction.constructionId(), vote);
        final var metadata = Optional.ofNullable(targetMetadata).orElse(Bytes.EMPTY);
        final var ledgerId = ledgerIdToProve(historyStore, metadata);
        final var category = isProofValid(explicitProofVote, ledgerId, metadata) ? VALID : INVALID;
        final var weightsByValidity = votes.entrySet().stream()
                .collect(groupingBy(
                        entry -> isProofValid(entry.getValue(), ledgerId, metadata),
                        summingLong(entry -> weights.sourceWeightOf(entry.getKey()))));
        final long validWeight =
                Optional.ofNullable(weightsByValidity.get(Boolean.TRUE)).orElse(0L);
        final boolean thresholdCrossed = validWeight >= weights.sourceWeightThreshold();
        if (thresholdCrossed) {
            // Votes for valid proofs are treated as congruent, we pick the valid proof
            // submitted by the node with the lowest id as a tiebreaker
            final var winningVote = votes.values().stream()
                    .filter(v -> isProofValid(v, ledgerId, metadata))
                    .findFirst()
                    .orElseThrow();
            finishProof(historyStore, winningVote.proof(), now);
        }
        // Let our prover know about the vote to optimize its choice of explicit or congruent voting
        requireNonNull(prover).observeProofVote(nodeId, vote, thresholdCrossed, category);
    }

    @Override
    public void cancelPendingWork() {
        final var sb = new StringBuilder("Canceled work on proof construction #").append(construction.constructionId());
        boolean canceledSomething = false;
        if (publicationFuture != null && !publicationFuture.isDone()) {
            sb.append("\n  * In-flight publication");
            publicationFuture.cancel(true);
            canceledSomething = true;
        }
        if (prover != null && prover.cancelPendingWork()) {
            sb.append("\n  * In-flight prover work");
            canceledSomething = true;
        }
        if (canceledSomething) {
            log.info(sb.toString());
        }
        historyProofMetrics.forgetConstruction(constructionId());
    }

    /**
     * Incorporates a single live vote into the in-memory state of this controller; used to determine when a
     * particular proof has enough votes to be completed. This is the path taken by {@link #addProofVote} as votes
     * arrive in consensus order, so a congruent vote whose referent is not yet known is rejected rather than held.
     * Rebuilding the tally from persisted votes instead uses {@link #replayPersistedVotes}, which does not assume
     * consensus order.
     *
     * @param nodeId the ID of the node that cast the vote
     * @param vote the vote to incorporate
     * @return whether the vote could still be incorporated (false once the proof is finished)
     */
    private boolean incorporateVote(final long nodeId, @NonNull final HistoryProofVote vote) {
        if (construction.hasTargetProof()) {
            log.info(
                    "Skipping vote from node{} for construction #{} because the proof is already finished",
                    nodeId,
                    construction.constructionId());
            return false;
        }
        if (vote.hasProof()) {
            votes.put(nodeId, new ExplicitProofVote(vote));
        } else if (vote.hasCongruentNodeId()) {
            final var congruentVote = votes.get(vote.congruentNodeIdOrThrow());
            if (congruentVote != null) {
                votes.put(nodeId, congruentVote);
            }
        }
        return true;
    }

    /**
     * Rebuilds the in-memory vote tally from the votes persisted in state, resolving each congruent vote to the
     * explicit vote it references independent of the order in which the votes are replayed.
     *
     * <p>Votes are returned from state in {@link HashMap} iteration order, which is <b>not</b> the consensus order in
     * which they were cast. The live {@link #incorporateVote} path assumes consensus order and drops a congruent vote
     * whose referent has not been seen yet; replaying with it would leave a reconnecting node with less counted weight
     * than the nodes that never restarted, so a later vote could complete the proof on some nodes but not others and
     * trigger an ISS. This resolver instead loads every explicit vote first and then follows references, so accepted
     * chains such as {@code node 0 -> node 1 -> node 2 (explicit)} are rebuilt regardless of replay order.
     *
     * @param persistedVotes the votes persisted in state, keyed by the node that cast them
     */
    private void replayPersistedVotes(@NonNull final Map<Long, HistoryProofVote> persistedVotes) {
        // Mirror incorporateVote's guard: once the proof is finished, no vote is incorporated.
        if (construction.hasTargetProof()) {
            return;
        }
        // Load every explicit vote into the tally, and index the congruent votes by the node they reference so their
        // dependencies can be resolved without relying on replay order.
        final Map<Long, List<Long>> congruentVotersByReferent = new TreeMap<>();
        final Deque<Long> resolvedVoters = new ArrayDeque<>();
        persistedVotes.forEach((nodeId, vote) -> {
            if (!weights.sourceNodeIds().contains(nodeId)) {
                return;
            }
            if (vote.hasProof()) {
                votes.put(nodeId, new ExplicitProofVote(vote));
                resolvedVoters.add(nodeId);
            } else if (vote.hasCongruentNodeId()) {
                congruentVotersByReferent
                        .computeIfAbsent(vote.congruentNodeIdOrThrow(), ignore -> new ArrayList<>())
                        .add(nodeId);
            }
        });
        // Starting from the explicit voters, resolve each congruent vote whose referent is now in the tally,
        // enqueueing the newly resolved voter so its own dependents resolve too, until no further votes resolve.
        while (!resolvedVoters.isEmpty()) {
            final long referent = resolvedVoters.poll();
            final var dependents = congruentVotersByReferent.remove(referent);
            if (dependents != null) {
                final var resolvedVote = votes.get(referent);
                dependents.forEach(dependent -> {
                    votes.put(dependent, resolvedVote);
                    resolvedVoters.add(dependent);
                });
            }
        }
        // Any congruent votes still unresolved reference a node that never cast a (transitively) explicit vote.
        // Accepted votes should never do this, so log the missing-reference or cyclic data instead of dropping it
        // silently.
        if (!congruentVotersByReferent.isEmpty()) {
            log.warn(
                    "Ignoring persisted congruent proof votes {} for construction #{} with unresolved references "
                            + "(missing referent or cyclic data)",
                    congruentVotersByReferent,
                    construction.constructionId());
        }
    }

    /**
     * Finishes the active construction, commits its proof to state, and notifies the history service.
     * @param historyStore the writable history store
     * @param proof the proof
     * @param now the current consensus time
     */
    private void finishProof(
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final HistoryProof proof,
            @NonNull final Instant now) {
        construction = historyStore.completeProof(construction.constructionId(), proof);
        historyProofMetrics.observeStage(constructionId(), HistoryProofMetrics.Stage.COMPLETED, now);
        historyProofMetrics.recordProofCompleted(constructionId(), construction.wrapsRetryCount());
        log.info(
                "History proof constructed (#{}, WRAPS-extensible? {})",
                construction.constructionId(),
                isWrapsExtensible(proof));
        historyService.onFinished(historyStore, construction, weights.targetNodeWeights());
        votes.clear();
        proofTagValidations.clear();
    }

    /**
     * Returns the ledger id whose chain of trust a proof for this construction must establish its metadata in.
     * <p>
     * A construction that grounds a chain of trust proves a new ledger id; namely, the ledger id of the history
     * this node expects it to ground. (That is, a ledger id not yet in state, or one replacing the ledger id in
     * state.) Every other construction extends the chain of trust of the ledger id in state.
     *
     * @param historyStore the history store
     * @param metadata the target metadata
     * @return the ledger id, or empty bytes if there is not yet any ledger id to prove
     */
    private Bytes ledgerIdToProve(@NonNull final ReadableHistoryStore historyStore, @NonNull final Bytes metadata) {
        if (groundsChainOfTrust(construction)) {
            final var groundingHistory = expectedTargetHistory(metadata);
            return groundingHistory == null ? Bytes.EMPTY : historyLibrary.ledgerIdOf(groundingHistory);
        }
        return Optional.ofNullable(historyStore.getLedgerId()).orElse(Bytes.EMPTY);
    }

    /**
     * Returns the target history this construction commits to, if this node can compute it; that is,
     * the hash of the target address book with every target node's proof key (or the sentinel key for a node that
     * did not publish one in time), paired with the given target metadata.
     *
     * @param metadata the target metadata
     * @return the expected history, or null if it cannot be computed
     */
    private @Nullable History expectedTargetHistory(@NonNull final Bytes metadata) {
        if (Bytes.EMPTY.equals(metadata)) {
            return null;
        }
        if (expectedTargetHistory == null || !expectedTargetHistory.metadata().equals(metadata)) {
            final var targetBook = AddressBook.from(weights.targetNodeWeights(), nodeId -> targetProofKeys
                    .getOrDefault(nodeId, MISSING_SCHNORR_KEY)
                    .toByteArray());
            try {
                expectedTargetHistory = new History(Bytes.wrap(historyLibrary.hashAddressBook(targetBook)), metadata);
            } catch (IllegalArgumentException e) {
                log.warn("Unable to compute the target history for construction #{}", constructionId(), e);
                return null;
            }
        }
        return expectedTargetHistory;
    }

    /**
     * Returns whether the given explicit vote's proof establishes the given metadata in the chain of trust of the
     * given ledger id and commits to the target history and selected keys expected by this node.
     *
     * @param vote the explicit vote
     * @param ledgerId the ledger id
     * @param metadata the target metadata
     * @return whether the vote's proof is valid
     */
    private boolean isProofValid(
            @NonNull final ExplicitProofVote vote, @NonNull final Bytes ledgerId, @NonNull final Bytes metadata) {
        requireNonNull(vote);
        requireNonNull(ledgerId);
        requireNonNull(metadata);
        final var validationKey = new ProofValidationKey(vote.tag(), ledgerId, metadata);
        return proofTagValidations.computeIfAbsent(validationKey, ignored -> {
            final var proof = vote.proof();
            if (!proof.targetHistoryOrElse(History.DEFAULT).equals(expectedTargetHistory(metadata))) {
                log.info(
                        "INVALID proof of unexpected target history {} (expected {})",
                        proof.targetHistory(),
                        expectedTargetHistory(metadata));
                return false;
            }
            if (!proof.targetProofKeys().equals(HistoryProver.proofKeyListFrom(targetProofKeys))) {
                log.info("INVALID proof of unexpected target proof keys for construction #{}", constructionId());
                return false;
            }
            if (!proof.targetNodeWeights().equals(HistoryProofAddressBooks.weightsFrom(weights.targetNodeWeights()))) {
                log.info(
                        "INVALID proof of unexpected canonical target membership for construction #{}",
                        constructionId());
                return false;
            }
            final var compressedProof = vote.compressedProofOrEmpty();
            final boolean valid = historyLibrary.verifyCompressedProof(
                    compressedProof, ledgerId.toByteArray(), metadata.toByteArray());
            log.info(
                    "{} compressed proof (SHA-384 '{}') over ('{}' || '{}')",
                    valid ? "VALID" : "INVALID",
                    Bytes.wrap(noThrowSha384HashOf(compressedProof)),
                    ledgerId,
                    metadata);
            return valid;
        });
    }

    /**
     * If the given failure reason is recoverable and retry budget remains, restarts WRAPS signing for this
     * construction and reinitializes in-memory prover state.
     *
     * @param reason the failure reason
     * @param historyStore the writable history store
     * @param tssConfig the TSS configuration
     * @return whether a retry was started
     */
    private boolean retryIfRecoverableFailure(
            @NonNull final String reason,
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(reason);
        requireNonNull(historyStore);
        requireNonNull(tssConfig);
        if (!WrapsHistoryProver.isRecoverableFailure(reason)) {
            return false;
        }
        final int maxWrapsRetries = tssConfig.maxWrapsRetries();
        if (construction.wrapsRetryCount() >= maxWrapsRetries) {
            log.warn(
                    "Construction #{} exhausted WRAPS retry budget ({}) after recoverable failure '{}'",
                    constructionId(),
                    maxWrapsRetries,
                    reason);
            return false;
        }
        if (prover != null) {
            prover.cancelPendingWork();
        }
        construction = historyStore.restartWrapsSigning(constructionId(), weights.sourceNodeIds());
        proofTagValidations.clear();
        historyProofMetrics.recordRetryStarted();
        prover = createProver(tssConfig);
        log.warn(
                "Restarted WRAPS signing for construction #{} (retry {}/{}) after recoverable failure '{}'",
                constructionId(),
                construction.wrapsRetryCount(),
                maxWrapsRetries,
                reason);
        return true;
    }

    /**
     * Applies a deterministic policy to recommend an assembly behavior at the given time.
     *
     * @param now the current consensus time
     * @return the recommendation
     */
    private boolean shouldAssemble(@NonNull final Instant now) {
        // The platform still excludes joining nodes, but every surviving platform member can publish,
        // including a member omitted from the sparse hinTS source authority. Compare identities rather
        // than counts: an unexpected extra key cannot stand in for a missing expected publisher.
        final var expectedPublishers =
                expectedProofKeyPublishers == null ? weights.targetNodeWeights().keySet() : expectedProofKeyPublishers;
        if (expectedPublishers.stream().allMatch(nodeId -> {
            final var key = targetProofKeys.get(nodeId);
            return key != null && !isIdentityKey(key);
        })) {
            log.info(
                    "All expected target nodes have published real proof keys for construction #{}",
                    construction.constructionId());
            return true;
        }
        if (now.isBefore(asInstant(construction.gracePeriodEndTimeOrThrow()))) {
            return false;
        } else {
            return publishedWeight() >= weights.targetWeightThreshold();
        }
    }

    /**
     * Ensures this node has published its local proof key, replacing a stale key for a later construction.
     */
    private void ensureProofKeyPublished() {
        if (publicationFuture == null
                && weights.targetIncludes(selfId)
                && !schnorrKeyPair.publicKey().equals(targetProofKeys.get(selfId))) {
            log.info("Publishing Schnorr key for construction #{}", construction.constructionId());
            try {
                publicationFuture = CompletableFuture.runAsync(
                                () -> submissions
                                        .submitProofKeyPublication(schnorrKeyPair.publicKey())
                                        .join(),
                                executor)
                        .exceptionally(e -> {
                            log.error("Error publishing proof key", e);
                            return null;
                        });
            } catch (RuntimeException e) {
                // Scheduling is local best-effort work and must not prevent consensus assembly updates.
                log.error("Error scheduling proof key publication", e);
            }
        }
    }

    /**
     * If the given publication was for a node in the target roster, updates the target proof keys.
     * @param publication the publication
     */
    private void maybeUpdateForProofKey(@NonNull final ProofKeyPublication publication) {
        final long nodeId = publication.nodeId();
        if (!weights.targetIncludes(nodeId)) {
            return;
        }
        final boolean comesLast = targetProofKeyPublications.values().stream()
                .allMatch(other -> PUBLICATION_ORDER.compare(other, publication) < 0);
        if (targetProofKeyPublications.put(nodeId, publication) == null && comesLast) {
            // Since keys are selected in order, a key that comes last only needs checking against those selected
            maybeSelect(publication);
            expectedTargetHistory = null;
            proofTagValidations.clear();
        } else {
            selectTargetProofKeys();
        }
    }

    /**
     * Selects the target proof keys from the latest publication of each target node, in order of adoption time
     * and then node id, skipping identity points and any key the target address book cannot be hashed with
     * (an invalid key, or a copy of a key already selected). An identity publication is an empty seat, not usable
     * signing coverage. The selection depends only on the publications, so given the same publications
     * it is the same live as when rebuilt from state.
     */
    private void selectTargetProofKeys() {
        final var publications = targetProofKeyPublications.values().stream()
                .filter(publication -> !isIdentityKey(publication.proofKey()))
                .sorted(PUBLICATION_ORDER)
                .toList();
        targetProofKeys.clear();
        publications.forEach(publication -> targetProofKeys.put(publication.nodeId(), publication.proofKey()));
        // Only when some key cannot be used do we need to check the keys one at a time
        if (!publications.isEmpty() && !canHashTargetAddressBook()) {
            targetProofKeys.clear();
            publications.forEach(this::maybeSelect);
        }
        expectedTargetHistory = null;
        proofTagValidations.clear();
    }

    /**
     * Selects a non-identity publication's key if the target address book can still be hashed with it.
     */
    private void maybeSelect(@NonNull final ProofKeyPublication publication) {
        if (isIdentityKey(publication.proofKey())) {
            log.warn(
                    "Ignoring identity proof key from node{} for construction #{}",
                    publication.nodeId(),
                    constructionId());
            return;
        }
        targetProofKeys.put(publication.nodeId(), publication.proofKey());
        if (!canHashTargetAddressBook()) {
            targetProofKeys.remove(publication.nodeId());
            log.warn(
                    "Ignoring proof key from node{} for construction #{} (invalid, or already used)",
                    publication.nodeId(),
                    constructionId());
        }
    }

    private boolean canHashTargetAddressBook() {
        // No keys make a too-large target address book usable, so leave that failure to the prover
        if (weights.targetNodeWeights().size() > MAX_ADDRESS_BOOK_SIZE) {
            return true;
        }
        try {
            historyLibrary.hashAddressBook(targetAddressBook());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns the target address book, with the sentinel key for each target node without a proof key.
     */
    private AddressBook targetAddressBook() {
        return AddressBook.from(weights.targetNodeWeights(), nodeId -> targetProofKeys
                .getOrDefault(nodeId, MISSING_SCHNORR_KEY)
                .toByteArray());
    }

    /**
     * Returns the target weight covered by selected non-identity proof keys.
     */
    private long publishedWeight() {
        return targetProofKeys.entrySet().stream()
                .filter(entry -> !isIdentityKey(entry.getValue()))
                .mapToLong(entry -> weights.targetWeightOf(entry.getKey()))
                .sum();
    }

    private HistoryProver createProver(@NonNull final TssConfig tssConfig) {
        final Map<Long, Bytes> sourceProofKeys = sourceProofKeys();
        return proverFactory.create(
                selfId,
                tssConfig,
                schnorrKeyPair,
                groundsChainOfTrust(construction) ? null : sourceProof,
                weights,
                sourceProofKeys,
                executor,
                historyLibrary,
                submissions);
    }

    /**
     * Returns the proof keys of the source nodes, which sign the target history.
     */
    private Map<Long, Bytes> sourceProofKeys() {
        return sourceProof == null || groundsChainOfTrust(construction)
                ? targetProofKeys
                // The canonical snapshot validator rejects duplicate IDs deterministically in advance(), rather
                // than allowing malformed imported state to throw while this controller is being constructed.
                : sourceProof.targetProofKeys().stream()
                        .collect(toMap(ProofKey::nodeId, ProofKey::key, (first, ignored) -> first));
    }

    private HistoryProofMetrics.Stage currentStage(@Nullable final Bytes metadata) {
        if (construction.hasTargetProof()) {
            return HistoryProofMetrics.Stage.COMPLETED;
        }
        if (construction.hasFailureReason()) {
            return HistoryProofMetrics.Stage.FAILED;
        }
        if (metadata == null) {
            return HistoryProofMetrics.Stage.WAITING_FOR_METADATA;
        }
        if (!construction.hasAssemblyStartTime() && !construction.hasWrapsSigningState()) {
            return HistoryProofMetrics.Stage.WAITING_FOR_ASSEMBLY;
        }
        final var phase =
                construction.wrapsSigningStateOrElse(WrapsSigningState.DEFAULT).phase();
        return switch (phase) {
            case R1 -> HistoryProofMetrics.Stage.WRAPS_R1;
            case R2 -> HistoryProofMetrics.Stage.WRAPS_R2;
            case R3 -> HistoryProofMetrics.Stage.WRAPS_R3;
            case AGGREGATE, POST_AGGREGATION, UNRECOGNIZED -> HistoryProofMetrics.Stage.WRAPS_AGGREGATE;
        };
    }
}
