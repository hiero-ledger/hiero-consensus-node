// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.schemas.V059HintsSchema;
import com.hedera.node.app.hints.schemas.V060HintsSchema;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of the {@link HintsService}.
 */
public class HintsServiceImpl implements HintsService {
    private static final Logger logger = LogManager.getLogger(HintsServiceImpl.class);

    @Deprecated
    private final Configuration bootstrapConfig;

    private final HintsServiceComponent component;

    private final HintsLibrary library;

    public HintsServiceImpl(
            @NonNull final Metrics metrics,
            @NonNull final Executor executor,
            @NonNull final AppContext appContext,
            @NonNull final HintsLibrary library,
            @NonNull final Configuration bootstrapConfig) {
        this.bootstrapConfig = requireNonNull(bootstrapConfig);
        this.library = requireNonNull(library);
        // Fully qualified for benefit of javadoc
        this.component = com.hedera.node.app.hints.impl.DaggerHintsServiceComponent.factory()
                .create(library, appContext, executor, metrics);
    }

    @VisibleForTesting
    HintsServiceImpl(
            @NonNull final Configuration bootstrapConfig,
            @NonNull final HintsServiceComponent component,
            @NonNull final HintsLibrary library) {
        this.bootstrapConfig = requireNonNull(bootstrapConfig);
        this.component = requireNonNull(component);
        this.library = requireNonNull(library);
    }

    @Override
    public boolean isReady() {
        return component.signingContext().isReady();
    }

    @Override
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Instant now,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(activeRosters);
        requireNonNull(hintsStore);
        requireNonNull(now);
        requireNonNull(tssConfig);
        switch (activeRosters.phase()) {
            case BOOTSTRAP, TRANSITION -> {
                final var construction = hintsStore.getOrCreateConstruction(activeRosters, now, tssConfig);
                if (!construction.hasHintsScheme()) {
                    final var controller =
                            component.controllers().getOrCreateFor(activeRosters, construction, hintsStore);
                    controller.advanceConstruction(now, hintsStore);
                }
            }
            case HANDOFF -> hintsStore.updateForHandoff(activeRosters);
        }
    }

    @Override
    public void executeCrsWork(@NonNull final WritableHintsStore hintsStore, @NonNull final Instant now) {
        requireNonNull(hintsStore);
        requireNonNull(now);

        final var controller = component.controllers().getAnyInProgress();
        if (controller.isEmpty()) {
            logger.info("No controller present to proceed for executing CRS work");
            return;
        }
        // Do the work needed to set the CRS for network and start the preprocessing vote
        if (hintsStore.getCrsState().stage() != COMPLETED) {
            controller.get().advanceCRSWork(now, hintsStore);
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
        final var tssConfig = bootstrapConfig.getConfigData(TssConfig.class);
        if (tssConfig.hintsEnabled()) {
            registry.register(new V059HintsSchema(component.signingContext()));
        }
        if (tssConfig.crsEnabled()) {
            registry.register(new V060HintsSchema(component.signingContext(), library));
        }
    }

    @Override
    public void initSigningForNextScheme(@NonNull final ReadableHintsStore hintsStore) {
        requireNonNull(hintsStore);
        component.signingContext().setConstruction(requireNonNull(hintsStore.getNextConstruction()));
    }

    @Override
    public CompletableFuture<Bytes> signFuture(@NonNull final Bytes blockHash) {
        if (!isReady()) {
            throw new IllegalStateException("hinTS service not ready to sign block hash " + blockHash);
        }
        final var signing = component.signings().computeIfAbsent(blockHash, component.signingContext()::newSigning);
        component.submissions().submitPartialSignature(blockHash).exceptionally(t -> {
            logger.warn("Failed to submit partial signature for block hash {}", blockHash, t);
            return null;
        });
        return signing.future();
    }

    @Override
    public void stop() {
        component.controllers().stop();
    }
}
