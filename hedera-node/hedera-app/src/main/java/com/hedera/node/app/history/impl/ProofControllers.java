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
     * Returns the ID of the current proof construction, or {@link #NO_CONSTRUCTION_ID} if there is none.
     */
    private long currentConstructionId() {
        return controller != null ? controller.constructionId() : NO_CONSTRUCTION_ID;
    }
}
