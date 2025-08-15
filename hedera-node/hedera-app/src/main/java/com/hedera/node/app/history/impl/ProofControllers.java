// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.spi.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ProofControllers {
    private static final long NO_CONSTRUCTION_ID = -1L;

    private final Executor executor;
    private final ProofKeysAccessor keyAccessor;
    private final HistoryLibrary library;
    private final HistorySubmissions submissions;
    private final Supplier<NodeInfo> selfNodeInfoSupplier;
    private final Consumer<HistoryProof> proofConsumer;

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
            @NonNull final HistoryLibrary library,
            @NonNull final HistorySubmissions submissions,
            @NonNull final Supplier<NodeInfo> selfNodeInfoSupplier,
            @NonNull final Consumer<HistoryProof> proofConsumer) {
        this.executor = requireNonNull(executor);
        this.keyAccessor = requireNonNull(keyAccessor);
        this.library = requireNonNull(library);
        this.submissions = requireNonNull(submissions);
        this.selfNodeInfoSupplier = requireNonNull(selfNodeInfoSupplier);
        this.proofConsumer = requireNonNull(proofConsumer);
    }

    /**
     * Creates a new controller for the given history proof construction, sourcing its rosters from the given store.
     *
     * @param activeRosters the active rosters
     * @param construction the construction
     * @param historyStore the history store
     * @return the result of the operation
     */
    public @NonNull ProofController getOrCreateFor(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final ReadableHistoryStore historyStore) {
        requireNonNull(activeRosters);
        requireNonNull(construction);
        requireNonNull(historyStore);
        if (currentConstructionId() != construction.constructionId()) {
            if (controller != null) {
                controller.cancelPendingWork();
            }
            controller = newControllerFor(activeRosters, construction, historyStore);
        }
        return requireNonNull(controller);
    }

    /**
     * Returns the in-progress controller for the proof construction with the given ID, if it exists.
     *
     * @param constructionId the ID of the proof construction
     * @return the controller, if it exists
     */
    public Optional<ProofController> getInProgressById(final long constructionId) {
        return currentConstructionId() == constructionId
                ? Optional.ofNullable(controller).filter(ProofController::isStillInProgress)
                : Optional.empty();
    }

    /**
     * Returns the in-progress controller for the hinTS construction with the given ID, if it exists.
     *
     * @return the controller, if it exists
     */
    public Optional<ProofController> getAnyInProgress() {
        return Optional.ofNullable(controller).filter(ProofController::isStillInProgress);
    }

    /**
     * Returns a new controller for the given active rosters and history proof construction.
     * @param activeRosters the active rosters
     * @param construction the proof construction
     * @param historyStore the history store
     * @return the controller
     */
    private ProofController newControllerFor(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final ReadableHistoryStore historyStore) {
        final var weights = activeRosters.transitionWeights();
        if (!weights.sourceNodesHaveTargetThreshold()) {
            return new InertProofController(construction.constructionId());
        } else {
            final var keyPublications = historyStore.getProofKeyPublications(weights.targetNodeIds());
            final var signaturePublications =
                    historyStore.getSignaturePublications(construction.constructionId(), weights.targetNodeIds());
            final var votes = historyStore.getVotes(construction.constructionId(), weights.sourceNodeIds());
            final var selfId = selfNodeInfoSupplier.get().nodeId();
            final var schnorrKeyPair = keyAccessor.getOrCreateSchnorrKeyPair(construction.constructionId());
            return new ProofControllerImpl(
                    selfId,
                    schnorrKeyPair,
                    historyStore.getLedgerId(),
                    construction,
                    weights,
                    executor,
                    library,
                    submissions,
                    keyPublications,
                    signaturePublications,
                    votes,
                    proofConsumer);
        }
    }

    /**
     * Returns the ID of the current proof construction, or {@link #NO_CONSTRUCTION_ID} if there is none.
     */
    private long currentConstructionId() {
        return controller != null ? controller.constructionId() : NO_CONSTRUCTION_ID;
    }
}
