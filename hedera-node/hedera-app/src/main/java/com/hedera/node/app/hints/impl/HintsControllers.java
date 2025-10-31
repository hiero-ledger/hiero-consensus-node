// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.hints.HintsService.maybeWeightsFrom;
import static com.hedera.node.app.hints.HintsService.partySizeForRosterNodeCount;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.info.NodeInfo;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Logic to get or create a {@link HintsController} for source/target roster hashes
 * relative to the current {@link HintsService} state and consensus time. We only support one
 * controller at a time, so if this class detects that a new controller is needed, it will cancel
 * any existing controller via {@link HintsController#cancelPendingWork()} and release its
 * reference to the cancelled controller.
 */
@Singleton
public class HintsControllers {
    private static final Logger log = LogManager.getLogger(HintsControllers.class);

    private static final long NO_CONSTRUCTION_ID = -1L;

    private final Executor executor;
    private final HintsKeyAccessor keyAccessor;
    private final HintsLibrary library;
    private final HintsSubmissions submissions;
    private final HintsContext context;
    private final Supplier<NodeInfo> selfNodeInfoSupplier;
    private final Supplier<Configuration> configurationSupplier;
    private final OnHintsFinished onHintsFinished;

    /**
     * May be null if the node has just started, or if the network has completed the most up-to-date
     * construction implied by its roster store.
     */
    @Nullable
    private HintsController controller;

    @Inject
    public HintsControllers(
            @NonNull final Executor executor,
            @NonNull final HintsKeyAccessor keyAccessor,
            @NonNull final HintsLibrary library,
            @NonNull final HintsSubmissions submissions,
            @NonNull final HintsContext context,
            @NonNull final Supplier<NodeInfo> selfNodeInfoSupplier,
            @NonNull final Supplier<Configuration> configurationSupplier,
            @NonNull final OnHintsFinished onHintsFinished) {
        this.executor = requireNonNull(executor);
        this.keyAccessor = requireNonNull(keyAccessor);
        this.context = requireNonNull(context);
        this.library = requireNonNull(library);
        this.submissions = requireNonNull(submissions);
        this.selfNodeInfoSupplier = requireNonNull(selfNodeInfoSupplier);
        this.configurationSupplier = requireNonNull(configurationSupplier);
        this.onHintsFinished = requireNonNull(onHintsFinished);
    }

    /**
     * Creates a new controller for the given hinTS construction, sourcing its rosters from the given store.
     *
     * @param activeRosters the active rosters
     * @param construction the hinTS construction
     * @param hintsStore the hinTS store
     * @param activeHintsConstruction the active hinTS construction
     * @return the result of the operation
     */
    public @NonNull HintsController getOrCreateFor(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final HintsConstruction construction,
            @NonNull final WritableHintsStore hintsStore,
            @Nullable final HintsConstruction activeHintsConstruction) {
        requireNonNull(activeRosters);
        requireNonNull(construction);
        requireNonNull(hintsStore);
        if (currentConstructionId() != construction.constructionId()) {
            if (controller != null) {
                controller.cancelPendingWork();
            }
            controller = newControllerFor(activeRosters, construction, hintsStore, activeHintsConstruction);
        }
        return requireNonNull(controller);
    }

    /**
     * Returns the in-progress controller for the hinTS construction with the given ID, if it exists.
     *
     * @param constructionId the ID of the hinTS construction
     * @return the controller, if it exists
     */
    public Optional<HintsController> getInProgressById(final long constructionId) {
        return currentConstructionId() == constructionId
                ? Optional.ofNullable(controller).filter(HintsController::isStillInProgress)
                : Optional.empty();
    }

    /**
     * Returns the in-progress controller for the hinTS construction with the given party size.
     *
     * @param m the number of parties
     * @return the controller, if it exists
     */
    public Optional<HintsController> getInProgressForNumParties(final int m) {
        return Optional.ofNullable(controller).filter(c -> c.hasNumParties(m));
    }

    /**
     * Returns the in-progress controller for the hinTS construction with the given party size.
     *
     * @return the controller, if it exists
     */
    public Optional<HintsController> getAnyInProgress() {
        return Optional.ofNullable(controller);
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
     * Returns a new controller for the given active rosters and hinTS construction.
     *
     * @param activeRosters the active rosters
     * @param construction  the hinTS construction
     * @param hintsStore    the hints store
     * @param activeHintsConstruction the active hinTS construction, if any
     * @return the controller
     */
    private HintsController newControllerFor(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final HintsConstruction construction,
            @NonNull final WritableHintsStore hintsStore,
            @Nullable final HintsConstruction activeHintsConstruction) {
        final var weights = activeRosters.transitionWeights(maybeWeightsFrom(activeHintsConstruction));
        if (!weights.sourceNodesHaveTargetThreshold()) {
            return new InertHintsController(construction.constructionId());
        } else {
            final int numParties = partySizeForRosterNodeCount(weights.targetRosterSize());
            log.info(
                    "Creating controller for construction #{} from nodes {} to {} with {} hinTS parties",
                    construction.constructionId(),
                    weights.sourceNodeIds(),
                    weights.targetNodeIds(),
                    numParties);
            final var publications = hintsStore.getHintsKeyPublications(weights.targetNodeIds(), numParties);
            log.info(
                    "Construction #{} already has {} relevant keys published [{}]",
                    construction.constructionId(),
                    publications.size(),
                    publications.stream()
                            .map(p -> "(" + p.nodeId() + " -> " + p.partyId() + ")")
                            .collect(joining(", ")));
            final var votes = hintsStore.getVotes(construction.constructionId(), weights.sourceNodeIds());
            final var selfId = selfNodeInfoSupplier.get().nodeId();
            final var blsKeyPair = keyAccessor.getOrCreateBlsPrivateKey(construction.constructionId());
            return new HintsControllerImpl(
                    selfId,
                    blsKeyPair,
                    construction,
                    weights,
                    executor,
                    library,
                    votes,
                    publications,
                    submissions,
                    context,
                    configurationSupplier,
                    hintsStore,
                    onHintsFinished);
        }
    }

    /**
     * Returns the ID of the current hinTS construction, or {@link #NO_CONSTRUCTION_ID} if there is none.
     */
    private long currentConstructionId() {
        return controller != null ? controller.constructionId() : NO_CONSTRUCTION_ID;
    }
}
