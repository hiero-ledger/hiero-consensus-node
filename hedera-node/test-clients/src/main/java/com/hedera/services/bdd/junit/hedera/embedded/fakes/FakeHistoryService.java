// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import com.hedera.hapi.block.stream.ChainOfTrustProof;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.history.impl.HistoryLibraryImpl;
import com.hedera.node.app.history.impl.HistoryServiceImpl;
import com.hedera.node.app.history.impl.OnProofFinished;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;

public class FakeHistoryService implements HistoryService {
    private final HistoryService delegate;
    private final Queue<Runnable> pendingHintsSubmissions = new ArrayDeque<>();

    public FakeHistoryService(@NonNull final AppContext appContext, @NonNull final Configuration bootstrapConfig) {
        delegate = new HistoryServiceImpl(
                new NoOpMetrics(),
                pendingHintsSubmissions::offer,
                appContext,
                new HistoryLibraryImpl(),
                bootstrapConfig);
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @Nullable final Bytes currentMetadata,
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final Instant now,
            @NonNull final TssConfig tssConfig,
            final boolean isActive,
            @Nullable final HintsConstruction activeConstruction) {
        delegate.reconcile(activeRosters, currentMetadata, historyStore, now, tssConfig, isActive, activeConstruction);
    }

    @NonNull
    @Override
    public ChainOfTrustProof getCurrentChainOfTrustProof(@NonNull final Bytes metadata) {
        return delegate.getCurrentChainOfTrustProof(metadata);
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        delegate.registerSchemas(registry);
    }

    @Override
    public HistoryHandlers handlers() {
        return delegate.handlers();
    }

    @Override
    public void onFinishedConstruction(@Nullable OnProofFinished cb) {
        delegate.onFinishedConstruction(cb);
    }

    @Override
    public void setLatestHistoryProof(@NonNull HistoryProof historyProof) {
        delegate.setLatestHistoryProof(historyProof);
    }

    @Override
    public void onFinished(@NonNull WritableHistoryStore historyStore, @NonNull HistoryProofConstruction construction) {
        delegate.onFinished(historyStore, construction);
    }
}
