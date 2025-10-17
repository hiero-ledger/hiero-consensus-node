// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.block.stream.ChainOfTrustProof;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.history.schemas.V059HistorySchema;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.concurrent.Executor;

/**
 * Default implementation of the {@link HistoryService}.
 */
public class HistoryServiceImpl implements HistoryService {
    @Deprecated
    private final Configuration bootstrapConfig;

    private final HistoryServiceComponent component;

    /**
     * If not null, the proof of the history ending at the current roster.
     */
    @Nullable
    private HistoryProof historyProof;

    @Nullable
    private OnProofFinished cb;

    public HistoryServiceImpl(
            @NonNull final Metrics metrics,
            @NonNull final Executor executor,
            @NonNull final AppContext appContext,
            @NonNull final HistoryLibrary library,
            @NonNull final Configuration bootstrapConfig) {
        this.bootstrapConfig = requireNonNull(bootstrapConfig);
        this.component = DaggerHistoryServiceComponent.factory().create(library, appContext, executor, metrics, this);
    }

    @VisibleForTesting
    public HistoryServiceImpl(
            @NonNull final HistoryServiceComponent component, @NonNull final Configuration bootstrapConfig) {
        this.component = requireNonNull(component);
        this.bootstrapConfig = requireNonNull(bootstrapConfig);
    }

    @Override
    public HistoryHandlers handlers() {
        return component.handlers();
    }

    @Override
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @Nullable final Bytes metadata,
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final Instant now,
            @NonNull final TssConfig tssConfig,
            final boolean isActive,
            @Nullable final HintsConstruction activeConstruction) {
        requireNonNull(activeRosters);
        requireNonNull(historyStore);
        requireNonNull(now);
        requireNonNull(tssConfig);
        switch (activeRosters.phase()) {
            case BOOTSTRAP, TRANSITION -> {
                final var construction = historyStore.getOrCreateConstruction(activeRosters, now, tssConfig);
                if (!construction.hasTargetProof()) {
                    final var controller = component
                            .controllers()
                            .getOrCreateFor(
                                    activeRosters,
                                    construction,
                                    historyStore,
                                    activeConstruction,
                                    tssConfig.wrapsEnabled());
                    controller.advanceConstruction(now, metadata, historyStore, isActive);
                }
            }
            case HANDOFF -> {
                // No-op
            }
        }
    }

    @Override
    public void onFinishedConstruction(@Nullable final OnProofFinished cb) {
        this.cb = cb;
    }

    @Override
    public void onFinished(
            @NonNull final WritableHistoryStore historyStore, @NonNull final HistoryProofConstruction construction) {
        requireNonNull(historyStore);
        requireNonNull(construction);
        if (cb != null) {
            cb.onFinished(historyStore, construction);
        }
    }

    @Override
    public void setLatestHistoryProof(@NonNull final HistoryProof historyProof) {
        this.historyProof = requireNonNull(historyProof);
    }

    @Override
    public boolean isReady() {
        // Not ready until there is a chain-of-trust proof for the genesis hinTS verification key
        return historyProof != null && historyProof.hasChainOfTrustProof();
    }

    @Override
    public @NonNull ChainOfTrustProof getCurrentChainOfTrustProof(@NonNull final Bytes metadata) {
        requireNonNull(metadata);
        requireNonNull(historyProof);
        final var targetMetadata = historyProof.targetHistoryOrThrow().metadata();
        if (!targetMetadata.equals(metadata)) {
            throw new IllegalArgumentException(
                    "Metadata '" + metadata + "' does not match proof (for '" + targetMetadata + "')");
        }
        return historyProof.chainOfTrustProofOrThrow();
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        final var tssConfig = bootstrapConfig.getConfigData(TssConfig.class);
        if (tssConfig.historyEnabled()) {
            registry.register(new V059HistorySchema(this));
        }
    }
}
