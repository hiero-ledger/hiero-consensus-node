// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.hapi.node.state.hints.CRSStage.GATHERING_CONTRIBUTIONS;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.cryptography.hints.HintsLibraryBridge;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.schemas.V059HintsSchema;
import com.hedera.node.app.hints.schemas.V060HintsSchema;
import com.hedera.node.app.hints.schemas.V073HintsSchema;
import com.hedera.node.app.info.TssStartupNetworks;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NetworkInfo;
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
        final var blockHashSigning = component.signings().computeIfAbsent(blockHash, b -> component
                .signingContext()
                .newSigningForActiveConstruction(b, () -> component.signings().remove(blockHash)));
        if (!(blockHashSigning instanceof HintsContext.Signing signing)) {
            throw new IllegalStateException("hinTS signing required for block hash " + blockHash);
        }
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
    public @NonNull TssSubmissions submissions() {
        return component.submissions();
    }

    @Override
    public @Nullable HintsConstruction activeConstruction() {
        return component.signingContext().activeConstruction();
    }

    @Override
    public void setActiveConstruction(@NonNull final HintsConstruction construction) {
        requireNonNull(construction);
        component.signingContext().setConstruction(construction);
        logger.info("Initialized hinTS signing context from active construction #{}", construction.constructionId());
    }

    @Override
    public void handoff(
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Roster previousRoster,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash,
            final boolean forceHandoff) {
        requireNonNull(hintsStore);
        requireNonNull(previousRoster);
        requireNonNull(adoptedRoster);
        requireNonNull(adoptedRosterHash);
        if (hintsStore.handoff(previousRoster, adoptedRoster, adoptedRosterHash, forceHandoff)) {
            HintsLibraryBridge.getInstance().resetCache();
            final var activeConstruction = requireNonNull(hintsStore.getActiveConstruction());
            component.signingContext().setConstruction(activeConstruction);
            logger.info("Updated hinTS construction in signing context to #{}", activeConstruction.constructionId());
        }
    }

    @Override
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Instant now,
            @NonNull final TssConfig tssConfig,
            final boolean isActive) {
        requireNonNull(activeRosters);
        requireNonNull(hintsStore);
        requireNonNull(now);
        requireNonNull(tssConfig);
        switch (activeRosters.phase()) {
            case BOOTSTRAP, TRANSITION -> {
                final var construction = hintsStore.getOrCreateConstruction(activeRosters, now, tssConfig);
                if (!construction.hasHintsScheme()) {
                    final var controller = component
                            .controllers()
                            .getOrCreateFor(activeRosters, construction, hintsStore, activeConstruction());
                    controller.advanceConstruction(now, hintsStore, isActive);
                }
            }
            case HANDOFF -> {
                // No-op
            }
        }
    }

    @Override
    public void executeCrsWork(
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Instant now,
            final boolean isActive,
            @NonNull final NetworkInfo networkInfo) {
        requireNonNull(hintsStore);
        requireNonNull(networkInfo);
        requireNonNull(now);
        final var controller = component.controllers().getAnyInProgress();
        // On the very first round the hinTS controller won't be available yet
        if (controller.isEmpty()) {
            return;
        }
        // Do the work needed to set the CRS for network and start the preprocessing vote
        var crsState = hintsStore.getCrsState();
        if (CRSState.DEFAULT.equals(crsState)) {
            // Must be a TSS cutover situation with tss.hintsEnabled = true but default state, so init here
            crsState = initialCrsState((short) HintsService.partySizeForRosterNodeCount(
                    networkInfo.addressBook().size()));
            hintsStore.setCrsState(crsState);
        }
        if (crsState.stage() != COMPLETED) {
            controller.get().advanceCrsWork(now, hintsStore, isActive);
        }
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
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates,
            @NonNull final Configuration configuration,
            final int networkSize) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        final var maybeGenesisNetwork = genesisTssNetwork();
        if (maybeGenesisNetwork.isPresent()) {
            logger.warn("Initializing dev-only hinTS genesis state and runtime from startup network JSON");
            final var activeConstruction =
                    TssStartupNetworks.initializeHintsState(writableStates, maybeGenesisNetwork.orElseThrow());
            if (activeConstruction.hasHintsScheme()) {
                setActiveConstruction(activeConstruction);
            }
            return true;
        }
        writableStates
                .<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID)
                .put(HintsConstruction.DEFAULT);
        writableStates
                .<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID)
                .put(HintsConstruction.DEFAULT);
        final var crsState = writableStates.<CRSState>getSingleton(CRS_STATE_STATE_ID);
        if (configuration.getConfigData(TssConfig.class).hintsEnabled()) {
            final var state = initialCrsState((short) HintsService.partySizeForRosterNodeCount(networkSize));
            crsState.put(state);
        } else {
            crsState.put(CRSState.DEFAULT);
        }
        return true;
    }

    private Optional<Network> genesisTssNetwork() {
        try {
            final var network = genesisNetworkSupplier.get();
            if (network == null) {
                return Optional.empty();
            }
            return TssStartupNetworks.hasTssMetadata(network) ? Optional.of(network) : Optional.empty();
        } catch (IllegalStateException e) {
            logger.debug("No genesis startup network available for hinTS bootstrap", e);
            return Optional.empty();
        }
    }

    @Override
    public void stop() {
        component.controllers().stop();
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
                .build();
    }
}
