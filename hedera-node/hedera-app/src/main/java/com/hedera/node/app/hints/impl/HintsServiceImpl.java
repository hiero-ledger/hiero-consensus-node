// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.roster.Roster;
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
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of the {@link HintsService}.
 */
public class HintsServiceImpl implements HintsService {
    private static final Logger logger = LogManager.getLogger(HintsServiceImpl.class);

    private final HintsServiceComponent component;

    private final HintsLibrary library;

    @NonNull
    private final AtomicReference<Roster> currentRoster = new AtomicReference<>();

    public HintsServiceImpl(
            @NonNull final Metrics metrics,
            @NonNull final Executor executor,
            @NonNull final AppContext appContext,
            @NonNull final HintsLibrary library,
            @NonNull final Duration blockPeriod) {
        this.library = requireNonNull(library);
        // Fully qualified for benefit of javadoc
        this.component = com.hedera.node.app.hints.impl.DaggerHintsServiceComponent.factory()
                .create(library, appContext, executor, metrics, currentRoster, blockPeriod);
    }

    @VisibleForTesting
    HintsServiceImpl(@NonNull final HintsServiceComponent component, @NonNull final HintsLibrary library) {
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
                    final var controller =
                            component.controllers().getOrCreateFor(activeRosters, construction, hintsStore);
                    controller.advanceConstruction(now, hintsStore, isActive);
                }
            }
            case HANDOFF -> hintsStore.updateForHandoff(activeRosters);
        }
        currentRoster.set(activeRosters.findRelatedRoster(activeRosters.currentRosterHash()));
    }

    @Override
    public void executeCrsWork(
            @NonNull final WritableHintsStore hintsStore, @NonNull final Instant now, final boolean isActive) {
        requireNonNull(hintsStore);
        requireNonNull(now);
        final var controller = component.controllers().getAnyInProgress();
        // On the very first round the hinTS controller won't be available yet
        if (controller.isEmpty()) {
            return;
        }
        // Do the work needed to set the CRS for network and start the preprocessing vote
        if (hintsStore.getCrsState().stage() != COMPLETED) {
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
        registry.register(new V060HintsSchema(component.signingContext(), library));
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
        final var signing = component.signings().computeIfAbsent(blockHash, b -> component
                .signingContext()
                .newSigning(b, requireNonNull(currentRoster.get()), () -> component
                        .signings()
                        .remove(blockHash)));
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
