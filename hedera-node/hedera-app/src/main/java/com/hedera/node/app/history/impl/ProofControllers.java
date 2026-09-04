// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.hints.HintsService.maybeWeightsFrom;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.info.NodeInfo;
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
     * Returns whether a new construction may fold onto the given proof; that is, whether the proof is extensible
     * with a WRAPS proof and carries the proving key hash the network is configured to use.
     * <p>
     * Folding requires identical public parameters at every step in the chain. A proof with no recorded proving
     * key hash is taken to have been built under the configured key.
     *
     * @param proof the proof to fold onto, if any
     * @param tssConfig the TSS configuration
     * @return whether the proof may be folded onto
     */
    public static boolean isFoldable(@Nullable final HistoryProof proof, @NonNull final TssConfig tssConfig) {
        requireNonNull(tssConfig);
        if (!isWrapsExtensible(proof)) {
            return false;
        }
        final var proofKeyHash = requireNonNull(proof).wrapsProvingKeyHash();
        return Bytes.EMPTY.equals(proofKeyHash) || proofKeyHash.equals(configuredProvingKeyHash(tssConfig));
    }

    /**
     * Returns whether a construction extending the given active proof must ground a fresh genesis WRAPS proof
     * rather than fold onto it.
     * <p>
     * A proof that is not WRAPS-extensible has nothing to fold onto, and grounds a genesis proof unconditionally.
     * Discarding a WRAPS-extensible proof built under a superseded proving key moves the ledger id, so it also
     * requires {@link TssConfig#wrapsAllowFreshGenesisOnKeyChange()} and a chain of trust that block proofs do
     * not yet carry.
     *
     * @param proof the active proof, if any
     * @param tssConfig the TSS configuration
     * @param chainOfTrustInUse whether block proofs already carry a chain-of-trust proof
     * @return whether a fresh genesis proof is needed
     */
    public static boolean needsFreshGenesis(
            @Nullable final HistoryProof proof, @NonNull final TssConfig tssConfig, final boolean chainOfTrustInUse) {
        requireNonNull(tssConfig);
        if (!tssConfig.wrapsEnabled() || isFoldable(proof, tssConfig)) {
            return false;
        }
        if (!isWrapsExtensible(proof)) {
            return true;
        }
        return tssConfig.wrapsAllowFreshGenesisOnKeyChange() && !chainOfTrustInUse;
    }

    /**
     * Returns whether the history service still has work to do for the given active construction: it has no
     * proof yet, its proof is the wrong kind for the current WRAPS setting, or its proof can no longer be
     * folded onto. While this holds, the network stays in the phase that grounds a chain of trust and leaves
     * any candidate roster alone.
     *
     * @param activeConstruction the active proof construction
     * @param tssConfig the TSS configuration
     * @param chainOfTrustInUse whether block proofs already carry a chain-of-trust proof
     * @return whether the active construction still needs work
     */
    public static boolean activeProofNeedsWork(
            @NonNull final HistoryProofConstruction activeConstruction,
            @NonNull final TssConfig tssConfig,
            final boolean chainOfTrustInUse) {
        requireNonNull(activeConstruction);
        requireNonNull(tssConfig);
        if (!activeConstruction.hasTargetProof()) {
            return true;
        }
        final var activeProof = activeConstruction.targetProofOrThrow();
        return (tssConfig.wrapsEnabled() != isWrapsExtensible(activeProof))
                || needsFreshGenesis(activeProof, tssConfig, chainOfTrustInUse);
    }

    /**
     * Returns whether the work implied by the given active construction grounds a genesis proof rather than
     * extending the chain to a new roster. A grounding construction proves the key of the roster it is
     * grounded in, so it takes the ACTIVE hinTS construction's key rather than the NEXT one's.
     *
     * @param activeConstruction the active proof construction
     * @param ledgerId the ledger id in state, or null if none has been established
     * @param tssConfig the TSS configuration
     * @param chainOfTrustInUse whether block proofs already carry a chain-of-trust proof
     * @return whether a genesis proof is being grounded
     */
    public static boolean groundsGenesisProof(
            @NonNull final HistoryProofConstruction activeConstruction,
            @Nullable final Bytes ledgerId,
            @NonNull final TssConfig tssConfig,
            final boolean chainOfTrustInUse) {
        requireNonNull(activeConstruction);
        requireNonNull(tssConfig);
        return ledgerId == null
                || (activeConstruction.hasTargetProof()
                        && needsFreshGenesis(activeConstruction.targetProofOrThrow(), tssConfig, chainOfTrustInUse));
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
     * Returns the configured WRAPS proving key hash as bytes, or {@link Bytes#EMPTY} if none is configured.
     *
     * @param tssConfig the TSS configuration
     * @return the configured proving key hash
     */
    public static Bytes configuredProvingKeyHash(@NonNull final TssConfig tssConfig) {
        requireNonNull(tssConfig);
        final var hex = tssConfig.wrapsProvingKeyHash();
        return hex.isBlank() ? Bytes.EMPTY : Bytes.fromHex(hex);
    }

    /**
     * Returns the ID of the current proof construction, or {@link #NO_CONSTRUCTION_ID} if there is none.
     */
    private long currentConstructionId() {
        return controller != null ? controller.constructionId() : NO_CONSTRUCTION_ID;
    }
}
