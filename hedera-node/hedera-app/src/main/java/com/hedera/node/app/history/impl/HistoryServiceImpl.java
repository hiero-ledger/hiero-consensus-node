// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.history.schemas.V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.LEDGER_ID_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.history.schemas.V071HistorySchema;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.SortedMap;
import java.util.concurrent.Executor;

/**
 * Default implementation of the {@link HistoryService}.
 */
public class HistoryServiceImpl implements HistoryService {
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
            @NonNull final HistoryLibrary library) {
        this.component = DaggerHistoryServiceComponent.factory().create(library, appContext, executor, metrics, this);
    }

    @VisibleForTesting
    public HistoryServiceImpl(@NonNull final HistoryServiceComponent component) {
        this.component = requireNonNull(component);
    }

    @Override
    public HistoryHandlers handlers() {
        return component.handlers();
    }

    @Override
    public Bytes historyProofVerificationKey() {
        return Bytes.wrap(component.library().wrapsVerificationKey());
    }

    @Override
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @Nullable final Bytes metadata,
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final Instant now,
            @NonNull final TssConfig tssConfig,
            final boolean isActive,
            @Nullable final HintsConstruction activeHintsConstruction) {
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
                                    activeHintsConstruction,
                                    historyStore.getActiveConstruction(),
                                    tssConfig);
                    controller.advanceConstruction(now, metadata, historyStore, isActive, tssConfig);
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
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final SortedMap<Long, Long> targetNodeWeights) {
        requireNonNull(historyStore);
        requireNonNull(construction);
        requireNonNull(targetNodeWeights);
        if (cb != null) {
            cb.onFinished(historyStore, construction, targetNodeWeights);
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
        registry.register(new V071HistorySchema(this));
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        writableStates.<ProtoBytes>getSingleton(LEDGER_ID_STATE_ID).put(ProtoBytes.DEFAULT);
        writableStates
                .<HistoryProofConstruction>getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID)
                .put(HistoryProofConstruction.DEFAULT);
        writableStates
                .<HistoryProofConstruction>getSingleton(NEXT_PROOF_CONSTRUCTION_STATE_ID)
                .put(HistoryProofConstruction.DEFAULT);
        return true;
    }
}
