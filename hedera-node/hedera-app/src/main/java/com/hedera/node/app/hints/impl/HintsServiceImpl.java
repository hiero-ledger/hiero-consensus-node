// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.hapi.node.state.hints.CRSStage.GATHERING_CONTRIBUTIONS;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.HintsLibrary.isSupportedPartySize;
import static com.hedera.node.app.hints.HintsService.maybeWeightsFrom;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.hedera.node.app.hints.schemas.V079HintsSchema.NEXT_CRS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.CrsContributor;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.KeyProvenanceSigner;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.schemas.V059HintsSchema;
import com.hedera.node.app.hints.schemas.V060HintsSchema;
import com.hedera.node.app.hints.schemas.V073HintsSchema;
import com.hedera.node.app.hints.schemas.V079HintsSchema;
import com.hedera.node.app.info.TssStartupNetworks;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.tss.TssSubmissions;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of the {@link HintsService}.
 */
public class HintsServiceImpl implements HintsService, OnHintsFinished {
    private static final Logger logger = LogManager.getLogger(HintsServiceImpl.class);

    private final HintsServiceComponent component;

    private final HintsLibrary library;

    private final Supplier<Network> genesisNetworkSupplier;
    private long unsupportedConstructionId = -1;

    @Nullable
    private HintsConstruction installedConstruction;

    @Nullable
    private CRSState installedCrs;

    @Nullable
    private HistoryProofConstruction installedHistory;

    private boolean installedRequiresHistory;

    @Nullable
    private OnHintsFinished cb;

    public HintsServiceImpl(
            @NonNull final Metrics metrics,
            @NonNull final Executor executor,
            @NonNull final AppContext appContext,
            @NonNull final HintsLibrary library,
            @NonNull final Duration blockPeriod,
            @NonNull final RsaContext rsaContext,
            @NonNull final ConcurrentMap<Bytes, BlockHashSigning> rsaSignings) {
        this(metrics, executor, appContext, library, blockPeriod, rsaContext, rsaSignings, () -> null);
    }

    public HintsServiceImpl(
            @NonNull final Metrics metrics,
            @NonNull final Executor executor,
            @NonNull final AppContext appContext,
            @NonNull final HintsLibrary library,
            @NonNull final Duration blockPeriod,
            @NonNull final RsaContext rsaContext,
            @NonNull final ConcurrentMap<Bytes, BlockHashSigning> rsaSignings,
            @NonNull final Supplier<Network> genesisNetworkSupplier) {
        this.library = requireNonNull(library);
        this.genesisNetworkSupplier = requireNonNull(genesisNetworkSupplier);
        // Fully qualified for benefit of javadoc
        this.component = com.hedera.node.app.hints.impl.DaggerHintsServiceComponent.factory()
                .create(library, appContext, executor, metrics, blockPeriod, this, rsaContext, rsaSignings);
    }

    @VisibleForTesting
    HintsServiceImpl(@NonNull final HintsServiceComponent component, @NonNull final HintsLibrary library) {
        this(component, library, () -> null);
    }

    @VisibleForTesting
    HintsServiceImpl(
            @NonNull final HintsServiceComponent component,
            @NonNull final HintsLibrary library,
            @NonNull final Supplier<Network> genesisNetworkSupplier) {
        this.component = requireNonNull(component);
        this.library = requireNonNull(library);
        this.genesisNetworkSupplier = requireNonNull(genesisNetworkSupplier);
    }

    @Override
    public void onFinishedConstruction(@Nullable final OnHintsFinished cb) {
        this.cb = cb;
    }

    @Override
    public void accept(
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final HintsConstruction construction,
            @NonNull final HintsContext context) {
        requireNonNull(hintsStore);
        requireNonNull(construction);
        requireNonNull(context);
        if (cb != null) {
            cb.accept(hintsStore, construction, context);
        }
    }

    @Override
    public boolean isReady() {
        return component.signingContext().isReady();
    }

    public Bytes verificationKey() {
        return component.signingContext().verificationKeyOrThrow();
    }

    @Override
    public @NonNull SigningResult sign(@NonNull final Bytes blockHash) {
        requireNonNull(blockHash);
        if (!isReady()) {
            throw new IllegalStateException("hinTS service not ready to sign block hash " + blockHash);
        }
        final var context = component.signingContext();
        // Keyed by the active construction, so an attempt a peer opened under another one is never reused
        final var attemptId = new SigningAttemptId(blockHash, context.constructionIdOrThrow());
        final var signing = component
                .signings()
                .computeIfAbsent(
                        attemptId,
                        id -> context.newSigningForActiveConstruction(
                                blockHash, () -> component.signings().remove(id)));
        // Submit under the construction captured by the signing attempt, not whatever construction is active after a
        // possible handoff.
        final var submissionFuture =
                component.submissions().submitPartialSignature(signing.constructionId(), blockHash);
        submissionFuture.exceptionally(t -> {
            logger.warn("Failed to submit partial signature for block hash {}", blockHash, t);
            return null;
        });
        return new SigningResult(signing, submissionFuture);
    }

    @Override
    public void onBlockStarted(final long blockNumber) {
        component.signingContext().onBlockStarted(blockNumber);
    }

    @Override
    public @NonNull TssSubmissions submissions() {
        return component.submissions();
    }

    @Override
    public @Nullable HintsConstruction activeConstruction() {
        return component.signingContext().activeConstruction();
    }

    @Override
    public @Nullable KeyProvenanceSigner keyProvenanceFor(@NonNull final ReadableHintsStore hintsStore) {
        requireNonNull(hintsStore);
        final var construction = hintsStore.getActiveConstruction();
        return construction.hasHintsScheme()
                ? new KeyProvenanceSigner(
                        library, component.keyAccessor(), construction, hintsStore.getCrsStateFor(construction))
                : null;
    }

    @Override
    public boolean requiresHistoryWeights() {
        return component.signingContext().requiresHistory();
    }

    @Override
    public void setActiveConstruction(
            @NonNull final HintsConstruction construction,
            @NonNull final CRSState crsState,
            @Nullable final HistoryProofConstruction historyConstruction) {
        installSigningConstruction(construction, crsState, historyConstruction);
    }

    private void installSigningConstruction(
            @NonNull final HintsConstruction construction,
            @NonNull final CRSState crsState,
            @Nullable final HistoryProofConstruction historyConstruction) {
        final var context = component.signingContext();
        final boolean requiresHistory = context.requiresHistory();
        if (Objects.equals(installedConstruction, construction)
                && Objects.equals(installedCrs, crsState)
                && installedRequiresHistory == requiresHistory
                && (!requiresHistory || Objects.equals(installedHistory, historyConstruction))
                && context.isReady()) {
            return;
        }
        final var generationWeights = requiresHistory
                ? HintsGenerationWeights.from(construction, historyConstruction)
                : HintsGenerationWeights.forScheme(construction);
        context.setConstruction(construction, crsState, generationWeights);
        installedConstruction = construction;
        installedCrs = crsState;
        installedHistory = requiresHistory ? historyConstruction : null;
        installedRequiresHistory = requiresHistory;
        logger.info("Initialized hinTS signing context from active construction #{}", construction.constructionId());
    }

    @Override
    public boolean handoff(
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Roster previousRoster,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash,
            @Nullable final HistoryProofConstruction historyConstruction) {
        requireNonNull(hintsStore);
        requireNonNull(previousRoster);
        requireNonNull(adoptedRoster);
        requireNonNull(adoptedRosterHash);
        final var context = component.signingContext();
        final boolean requiresHistory = context.requiresHistory();
        // Real history must be checked before either service promotes state. Compatibility retains the
        // legacy no-op behavior when there is no next construction to hand off.
        final var generationWeights = requiresHistory
                ? HintsGenerationWeights.from(hintsStore.getNextConstruction(), historyConstruction)
                : null;
        if (hintsStore.handoff(previousRoster, adoptedRoster, adoptedRosterHash)) {
            final var activeConstruction = requireNonNull(hintsStore.getActiveConstruction());
            final var crsState = hintsStore.getCrsStateFor(activeConstruction);
            context.setConstruction(
                    activeConstruction,
                    crsState,
                    requiresHistory
                            ? requireNonNull(generationWeights)
                            : HintsGenerationWeights.forScheme(activeConstruction));
            installedConstruction = activeConstruction;
            installedCrs = crsState;
            installedHistory = requiresHistory ? historyConstruction : null;
            installedRequiresHistory = requiresHistory;
            logger.info("Updated hinTS construction in signing context to #{}", activeConstruction.constructionId());
            return true;
        }
        return false;
    }

    @Override
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Instant now,
            @NonNull final TssConfig tssConfig,
            final boolean isActive) {
        reconcile(activeRosters, hintsStore, now, now, tssConfig, isActive);
    }

    @Override
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Instant now,
            @NonNull final Instant crsWorkTime,
            @NonNull final TssConfig tssConfig,
            final boolean isActive) {
        reconcile(activeRosters, hintsStore, now, crsWorkTime, tssConfig, isActive, null);
    }

    @Override
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Instant now,
            @NonNull final Instant crsWorkTime,
            @NonNull final TssConfig tssConfig,
            final boolean isActive,
            @Nullable final HistoryProofConstruction activeHistoryConstruction) {
        try {
            reconcileConstruction(
                    activeRosters, hintsStore, now, crsWorkTime, tssConfig, isActive, activeHistoryConstruction);
        } catch (HintsGenerationWeights.InvalidGenerationException e) {
            // Signing installation can reject the generation before the controller factory is reached.
            // Preserve existing signing attempts while stopping all preprocessing work and vote admission.
            component.controllers().stop();
            throw e;
        }
    }

    @Override
    public void activateSigning(
            @NonNull final ReadableHintsStore hintsStore,
            @NonNull final TssConfig tssConfig,
            @Nullable final HistoryProofConstruction activeHistoryConstruction) {
        requireNonNull(hintsStore);
        requireNonNull(tssConfig);
        final var active = hintsStore.getActiveConstruction();
        if (active != null && active.hasHintsScheme()) {
            final boolean requiresHistory = HintsGenerationWeights.requiresHistory(tssConfig);
            if (!requiresHistory
                    || (activeHistoryConstruction != null
                            && activeHistoryConstruction.hasTargetProof()
                            && !activeHistoryConstruction
                                    .targetProofOrThrow()
                                    .targetNodeWeights()
                                    .isEmpty())) {
                // Singleton reads may reparse equal records. Install only changed values, without rebuilding weights or
                // taking the signing write lock.
                installSigningConstruction(active, hintsStore.getCrsStateFor(active), activeHistoryConstruction);
            }
        }
    }

    private void reconcileConstruction(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Instant now,
            @NonNull final Instant crsWorkTime,
            @NonNull final TssConfig tssConfig,
            final boolean isActive,
            @Nullable final HistoryProofConstruction activeHistoryConstruction) {
        requireNonNull(activeRosters);
        requireNonNull(hintsStore);
        requireNonNull(now);
        requireNonNull(crsWorkTime);
        requireNonNull(tssConfig);
        activateSigning(hintsStore, tssConfig, activeHistoryConstruction);
        switch (activeRosters.phase()) {
            case BOOTSTRAP, TRANSITION -> {
                var construction = hintsStore.getOrCreateConstruction(activeRosters, now, tssConfig);
                // Refuse an unsupported candidate without interrupting the active network's signing.
                final int requiredParties = HintsService.partySizeForRoster(activeRosters.targetRoster());
                final int activeParties = hintsStore.getCrsState().numParties();
                if (!isSupportedPartySize(requiredParties)
                        || (activeParties != 0 && !isSupportedPartySize(activeParties))
                        || (construction.numParties() != 0 && !isSupportedPartySize(construction.numParties()))) {
                    component.controllers().stop();
                    if (unsupportedConstructionId != construction.constructionId()) {
                        unsupportedConstructionId = construction.constructionId();
                        logger.warn(
                                "Cannot construct hinTS for candidate #{}: unsupported CRS capacity (required={}, active={}, bound={}, maximum={})",
                                construction.constructionId(),
                                requiredParties,
                                activeParties,
                                construction.numParties(),
                                HintsLibrary.MAX_PARTIES);
                    }
                    return;
                }
                if (construction.hasHintsScheme()) {
                    component.controllers().stop();
                    return;
                }
                construction = bindCrs(activeRosters, hintsStore, construction, crsWorkTime, tssConfig);
                construction = startKeyCollectionIfReady(activeRosters, hintsStore, construction, now, tssConfig);
                final var controller = component
                        .controllers()
                        .getOrCreateFor(
                                activeRosters,
                                construction,
                                hintsStore,
                                hintsStore.getActiveConstruction(),
                                activeHistoryConstruction);
                controller.advanceCrsWork(crsWorkTime, hintsStore, isActive);
                if (hintsStore.getCrsStateFor(construction).stage() != COMPLETED) {
                    return;
                }
                startKeyCollectionIfReady(activeRosters, hintsStore, construction, now, tssConfig);
                controller.advanceConstruction(now, hintsStore, isActive);
            }
            case HANDOFF -> {
                // A withdrawn candidate must not leave an old controller accepting publications.
                component.controllers().stop();
                hintsStore.abandonNextConstruction();
            }
        }
    }

    private HintsConstruction startKeyCollectionIfReady(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final HintsConstruction construction,
            @NonNull final Instant now,
            @NonNull final TssConfig config) {
        if (hintsStore.getCrsStateFor(construction).stage() == COMPLETED
                && !construction.hasGracePeriodEndTime()
                && !construction.hasPreprocessingStartTime()
                && !construction.hasHintsScheme()) {
            final var gracePeriod = activeRosters.phase() == ActiveRosters.Phase.BOOTSTRAP
                    ? config.bootstrapHintsKeyGracePeriod()
                    : config.transitionHintsKeyGracePeriod();
            return hintsStore.startHintsKeyGracePeriod(construction.constructionId(), now, now.plus(gracePeriod));
        }
        return construction;
    }

    private HintsConstruction bindCrs(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final HintsConstruction construction,
            @NonNull final Instant now,
            @NonNull final TssConfig config) {
        if (construction.crsId() != 0) {
            return construction;
        }
        final var activeCrs = hintsStore.getCrsState();
        final int requiredParties = HintsService.partySizeForRoster(activeRosters.targetRoster());
        final int numParties = Math.max(activeCrs.numParties(), requiredParties);
        if (activeCrs.stage() == COMPLETED
                && activeCrs.ceremonyId() != 0
                && activeCrs.numParties() >= requiredParties) {
            // Shrinking the roster never shrinks n. Both the bytes and every hinTS call retain the
            // established degree, avoiding exposure of larger-degree hints under a smaller scheme.
            hintsStore.setNextCrsState(CRSState.DEFAULT);
            return hintsStore.bindConstructionToCrs(construction.constructionId(), activeCrs.ceremonyId(), numParties);
        }
        final var weights = activeRosters.transitionWeights(maybeWeightsFrom(hintsStore.getActiveConstruction()));
        final var contributors = weights.sourceNodeWeights().entrySet().stream()
                .map(e -> new CrsContributor(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(CrsContributor::nodeId))
                .toList();
        final long ceremonyId = hintsStore.allocateCrsId();
        // initCRS is only the public seed. The new ceremony still requires fresh, independently
        // generated entropy from a threshold of the frozen source roster before it is usable.
        final var seed = library.newCrs((short) numParties);
        final var crs = CRSState.newBuilder()
                .ceremonyId(ceremonyId)
                .numParties(numParties)
                .sourceRosterHash(construction.sourceRosterHash())
                .targetRosterHash(construction.targetRosterHash())
                .constructionId(construction.constructionId())
                .contributors(contributors)
                .attempt(1)
                .initialCrs(seed)
                .crs(seed)
                .stage(GATHERING_CONTRIBUTIONS)
                .nextContributingNodeId(
                        contributors.isEmpty() ? null : contributors.getFirst().nodeId())
                .contributionEndTime(asTimestamp(now.plus(config.crsUpdateContributionTime())))
                .build();
        if (hintsStore.getActiveConstruction().constructionId() == construction.constructionId()) {
            hintsStore.setCrsState(crs);
        } else {
            hintsStore.setNextCrsState(crs);
        }
        return hintsStore.bindConstructionToCrs(construction.constructionId(), ceremonyId, numParties);
    }

    @Override
    public @NonNull Bytes activeVerificationKeyOrThrow() {
        return component.signingContext().verificationKeyOrThrow();
    }

    @Override
    public HintsHandlers handlers() {
        return component.handlers();
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V059HintsSchema());
        registry.register(new V060HintsSchema(component.signingContext()));
        registry.register(new V073HintsSchema(library, component.signingContext()));
        registry.register(new V079HintsSchema(library));
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates,
            @NonNull final Configuration configuration,
            final int networkSize) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        final var maybeGenesisNetwork = genesisTssNetwork(configuration);
        if (maybeGenesisNetwork.isPresent()) {
            logger.warn("Initializing dev-only hinTS genesis state and runtime from startup network JSON");
            TssStartupNetworks.initializeHintsState(writableStates, maybeGenesisNetwork.orElseThrow(), configuration);
            // Signing is restored after the matching history proof has also been initialized.
            return true;
        }
        writableStates
                .<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID)
                .put(HintsConstruction.DEFAULT);
        writableStates
                .<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID)
                .put(HintsConstruction.DEFAULT);
        writableStates.<CRSState>getSingleton(NEXT_CRS_STATE_ID).put(CRSState.DEFAULT);
        final var crsState = writableStates.<CRSState>getSingleton(CRS_STATE_STATE_ID);
        if (configuration.getConfigData(TssConfig.class).hintsEnabled()) {
            final var state = initialCrsState((short) HintsService.partySizeForRosterNodeCount(networkSize));
            crsState.put(state);
        } else {
            crsState.put(CRSState.DEFAULT);
        }
        return true;
    }

    private Optional<Network> genesisTssNetwork(@NonNull final Configuration configuration) {
        try {
            final var network = genesisNetworkSupplier.get();
            if (network == null) {
                return Optional.empty();
            }
            return TssStartupNetworks.shouldImportTssMetadata(network, configuration)
                    ? Optional.of(network)
                    : Optional.empty();
        } catch (IllegalStateException e) {
            logger.debug("No genesis startup network available for hinTS bootstrap", e);
            return Optional.empty();
        }
    }

    @Override
    public void stop() {
        component.controllers().stop();
        component.signingContext().clear();
        installedConstruction = null;
        installedCrs = null;
        installedHistory = null;
    }

    /**
     * Creates the initial CRS state for the given number of parties.
     * @param initialCrsParties the number of parties in the initial CRS scheme
     * @return the initial CRS state
     */
    private CRSState initialCrsState(final short initialCrsParties) {
        final var initialCrs = library.newCrs(initialCrsParties);
        return CRSState.newBuilder()
                .stage(GATHERING_CONTRIBUTIONS)
                .nextContributingNodeId(0L)
                .crs(initialCrs)
                .initialCrs(initialCrs)
                .numParties(initialCrsParties)
                .build();
    }
}
