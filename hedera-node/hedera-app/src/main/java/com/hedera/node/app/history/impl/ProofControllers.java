// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.hints.HintsService.maybeWeightsFrom;
import static com.hedera.node.app.history.HistoryService.isCompleted;
import static com.hedera.node.app.tss.TssBlockHashSigner.usesChainOfTrustProof;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ProofControllers {
    private static final long NO_CONSTRUCTION_ID = -1L;

    private final Executor executor;
    private final ProofKeysAccessor keyAccessor;
    private final HistoryLibrary historyLibrary;
    private final HistoryService historyService;
    private final HistoryProofMetrics historyProofMetrics;
    private final HistorySubmissions submissions;
    private final WrapsMpcStateMachine machine;
    private final Supplier<NodeInfo> selfNodeInfoSupplier;

    /**
     * May be null if the node has just started, or if the network has completed the most up-to-date
     * construction implied by its roster store.
     */
    @Nullable
    private ProofController controller;

    @Inject
    public ProofControllers(
            @NonNull final Executor executor,
            @NonNull final ProofKeysAccessor keyAccessor,
            @NonNull final HistoryLibrary historyLibrary,
            @NonNull final HistorySubmissions submissions,
            @NonNull final Supplier<NodeInfo> selfNodeInfoSupplier,
            @NonNull final HistoryService historyService,
            @NonNull final HistoryProofMetrics historyProofMetrics,
            @NonNull final WrapsMpcStateMachine machine) {
        this.executor = requireNonNull(executor);
        this.keyAccessor = requireNonNull(keyAccessor);
        this.historyLibrary = requireNonNull(historyLibrary);
        this.submissions = requireNonNull(submissions);
        this.selfNodeInfoSupplier = requireNonNull(selfNodeInfoSupplier);
        this.historyService = requireNonNull(historyService);
        this.historyProofMetrics = requireNonNull(historyProofMetrics);
        this.machine = requireNonNull(machine);
    }

    /**
     * Creates a new controller for the given history proof construction, sourcing its rosters from the given store.
     *
     * @param activeRosters the active rosters
     * @param construction the construction
     * @param historyStore the history store
     * @param activeHintsConstruction the active hinTS construction, if any
     * @param activeProofConstruction the active proof construction, if any
     * @param tssConfig the TSS configuration
     * @return the result of the operation
     */
    public @NonNull ProofController getOrCreateFor(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final ReadableHistoryStore historyStore,
            @Nullable final HintsConstruction activeHintsConstruction,
            @NonNull final HistoryProofConstruction activeProofConstruction,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(activeRosters);
        requireNonNull(construction);
        requireNonNull(historyStore);
        requireNonNull(activeProofConstruction);
        if (currentConstructionId() != construction.constructionId()) {
            if (controller != null) {
                controller.cancelPendingWork();
            }
            controller = newControllerFor(
                    activeRosters,
                    construction,
                    historyStore,
                    activeHintsConstruction,
                    activeProofConstruction,
                    tssConfig);
        }
        return requireNonNull(controller);
    }

    /**
     * Returns the in-progress controller for the proof construction with the given ID, if it exists.
     *
     * @param constructionId the ID of the proof construction
     * @param tssConfig the TSS configuration
     * @return the controller, if it exists
     */
    public Optional<ProofController> getInProgressById(final long constructionId, @NonNull final TssConfig tssConfig) {
        return currentConstructionId() == constructionId
                ? Optional.ofNullable(controller).filter(pc -> pc.isStillInProgress(tssConfig))
                : Optional.empty();
    }

    /**
     * Returns the in-progress controller for the hinTS construction with the given ID, if it exists.
     * @param tssConfig the TSS configuration
     * @return the controller, if it exists
     */
    public Optional<ProofController> getAnyInProgress(@NonNull final TssConfig tssConfig) {
        return Optional.ofNullable(controller).filter(pc -> pc.isStillInProgress(tssConfig));
    }

    /**
     * Stops the current controller, if it exists.
     */
    public void stop() {
        if (controller != null) {
            controller.cancelPendingWork();
            controller = null;
        }
    }

    /**
     * Returns a new controller for the given active rosters and history proof construction.
     *
     * @param activeRosters the active rosters
     * @param construction the proof construction
     * @param historyStore the history store
     * @param activeHintsConstruction the active hinTS construction, if any
     * @param activeProofConstruction the active proof construction
     * @param tssConfig the TSS configuration
     * @return the controller
     */
    private ProofController newControllerFor(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final ReadableHistoryStore historyStore,
            @Nullable final HintsConstruction activeHintsConstruction,
            @NonNull final HistoryProofConstruction activeProofConstruction,
            @NonNull final TssConfig tssConfig) {
        final var weights = activeRosters.transitionWeights(maybeWeightsFrom(activeHintsConstruction));
        if (!weights.sourceNodesHaveTargetThreshold()) {
            return new InertProofController(construction.constructionId());
        } else {
            final var keyPublications = historyStore.getProofKeyPublications(weights.targetNodeIds());
            final var wrapsMessagePublications =
                    historyStore.getWrapsMessagePublications(construction.constructionId(), weights.targetNodeIds());
            final var votes = historyStore.getVotes(construction.constructionId(), weights.sourceNodeIds());
            final var selfId = selfNodeInfoSupplier.get().nodeId();
            final var schnorrKeyPair = keyAccessor.getOrCreateSchnorrKeyPair(construction.constructionId());
            final var sourceProof = activeProofConstruction.targetProof();
            final HistoryProver.Factory proverFactory = (s, t, k, p, w, r, x, l, m) -> new WrapsHistoryProver(
                    s, t.wrapsMessageGracePeriod(), k, p, w, r, CompletableFuture::delayedExecutor, x, l, m, machine);
            return new ProofControllerImpl(
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
        }
    }

    /**
     * Returns whether the given proof is extensible with a WRAPS proof.
     * @param proof the proof
     * @return whether the proof is extensible with a WRAPS proof
     */
    public static boolean isWrapsExtensible(@Nullable final HistoryProof proof) {
        return proof != null && !Bytes.EMPTY.equals(proof.uncompressedWrapsProof());
    }

    /**
     * Returns whether the given construction grounds a chain of trust rather than extending one to a new roster;
     * that is, whether it has the same roster as both source and target. This is the shape of the genesis
     * construction, and of any construction later created to build a fresh genesis WRAPS proof for the roster
     * the network already has.
     *
     * @param construction the construction
     * @return whether the construction grounds a chain of trust
     */
    public static boolean groundsChainOfTrust(@NonNull final HistoryProofConstruction construction) {
        requireNonNull(construction);
        return !Bytes.EMPTY.equals(construction.sourceRosterHash())
                && construction.sourceRosterHash().equals(construction.targetRosterHash());
    }

    /**
     * Returns whether a fresh genesis WRAPS proof is requested for the current roster. It is requested for the
     * first round after an upgrade when {@link TssConfig#needsFreshGenesisWrapsProof()} is set, so the request
     * is made exactly once per upgrade and on every node. A fresh genesis proof may move the ledger id, which
     * downstream verifiers cannot follow once block proofs carry the chain of trust; so it is never requested
     * once the network is configured to cut over to that, nor once it has done so.
     *
     * @param tssConfig the TSS configuration
     * @param blockStreamConfig the block stream configuration
     * @param postUpgradeWorkPending whether the post-upgrade work of the current round is still pending
     * @return whether a fresh genesis WRAPS proof is requested
     */
    public static boolean freshGenesisRequested(
            @NonNull final TssConfig tssConfig,
            @NonNull final BlockStreamConfig blockStreamConfig,
            final boolean postUpgradeWorkPending) {
        requireNonNull(tssConfig);
        requireNonNull(blockStreamConfig);
        return postUpgradeWorkPending
                && tssConfig.wrapsEnabled()
                && tssConfig.needsFreshGenesisWrapsProof()
                && !blockStreamConfig.enableCutover()
                && !usesChainOfTrustProof(tssConfig, blockStreamConfig);
    }

    /**
     * Returns whether the given next construction is building a fresh genesis WRAPS proof for the current
     * roster; that is, whether it grounds a chain of trust but is not yet complete.
     *
     * @param nextConstruction the next proof construction
     * @param tssConfig the TSS configuration
     * @return whether a fresh genesis proof is in progress
     */
    public static boolean freshGenesisInProgress(
            @NonNull final HistoryProofConstruction nextConstruction, @NonNull final TssConfig tssConfig) {
        requireNonNull(nextConstruction);
        requireNonNull(tssConfig);
        return groundsChainOfTrust(nextConstruction) && !isCompleted(nextConstruction, tssConfig);
    }

    /**
     * Returns whether the history service still has work to do before the network can act on a candidate
     * roster: the active construction has no proof yet, its proof is the wrong kind for the current WRAPS
     * setting, or a fresh genesis proof has been requested or is still being built. While this holds, the
     * network stays in the phase that grounds a chain of trust and leaves any candidate roster alone.
     *
     * @param activeConstruction the active proof construction
     * @param nextConstruction the next proof construction
     * @param tssConfig the TSS configuration
     * @param freshGenesisRequested whether a fresh genesis proof is requested this round
     * @return whether the active proof still needs work
     */
    public static boolean activeProofNeedsWork(
            @NonNull final HistoryProofConstruction activeConstruction,
            @NonNull final HistoryProofConstruction nextConstruction,
            @NonNull final TssConfig tssConfig,
            final boolean freshGenesisRequested) {
        requireNonNull(activeConstruction);
        requireNonNull(nextConstruction);
        requireNonNull(tssConfig);
        if (!activeConstruction.hasTargetProof()) {
            return true;
        }
        return tssConfig.wrapsEnabled() != isWrapsExtensible(activeConstruction.targetProofOrThrow())
                || freshGenesisRequested
                || freshGenesisInProgress(nextConstruction, tssConfig);
    }

    /**
     * Returns whether the history work of the current round grounds a genesis proof rather than extending the
     * chain to a new roster. A grounding construction proves the key of the roster it is grounded in, so it
     * takes the ACTIVE hinTS construction's key rather than the NEXT one's.
     *
     * @param activeConstruction the active proof construction
     * @param nextConstruction the next proof construction
     * @param ledgerId the ledger id in state, or null if none has been established
     * @param tssConfig the TSS configuration
     * @param freshGenesisRequested whether a fresh genesis proof is requested this round
     * @return whether a genesis proof is being grounded
     */
    public static boolean groundsGenesisProof(
            @NonNull final HistoryProofConstruction activeConstruction,
            @NonNull final HistoryProofConstruction nextConstruction,
            @Nullable final Bytes ledgerId,
            @NonNull final TssConfig tssConfig,
            final boolean freshGenesisRequested) {
        requireNonNull(activeConstruction);
        requireNonNull(nextConstruction);
        requireNonNull(tssConfig);
        return ledgerId == null
                || (tssConfig.wrapsEnabled()
                        && activeConstruction.hasTargetProof()
                        && !isWrapsExtensible(activeConstruction.targetProofOrThrow()))
                || freshGenesisRequested
                || freshGenesisInProgress(nextConstruction, tssConfig);
    }

    /**
     * Returns the ledger id a completed grounding proof establishes, or null if it is the one already in state.
     * A proof grounded in the same address book anchors at the same hash, so there is no new id to publish.
     *
     * @param proof the proof that grounded the chain of trust
     * @param currentLedgerId the ledger id in state, if any
     * @return the new ledger id, or null if unchanged
     */
    @Nullable
    public static Bytes reAnchoredLedgerId(@NonNull final HistoryProof proof, @Nullable final Bytes currentLedgerId) {
        requireNonNull(proof);
        final var anchor = proof.targetHistoryOrThrow().addressBookHash();
        return anchor.equals(currentLedgerId) ? null : anchor;
    }

    /**
     * Returns the ID of the current proof construction, or {@link #NO_CONSTRUCTION_ID} if there is none.
     */
    private long currentConstructionId() {
        return controller != null ? controller.constructionId() : NO_CONSTRUCTION_ID;
    }
}
