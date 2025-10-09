// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.hints.impl.HintsServiceImpl;
import com.hedera.node.app.hints.impl.OnHintsFinished;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.BlockStreamConfig;
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
import java.util.concurrent.CompletableFuture;

public class FakeHintsService implements HintsService {
    private final HintsService delegate;
    private final Queue<Runnable> pendingHintsSubmissions = new ArrayDeque<>();

    public FakeHintsService(@NonNull final AppContext appContext, @NonNull final Configuration bootstrapConfig) {
        delegate = new HintsServiceImpl(
                new NoOpMetrics(),
                pendingHintsSubmissions::offer,
                appContext,
                new HintsLibraryImpl(),
                bootstrapConfig.getConfigData(BlockStreamConfig.class).blockPeriod());
    }

    @Override
    public void initCurrentRoster(@NonNull final Roster roster) {
        delegate.initCurrentRoster(roster);
    }

    @Override
    public @NonNull Bytes activeVerificationKeyOrThrow() {
        return delegate.activeVerificationKeyOrThrow();
    }

    @Override
    public void onFinishedConstruction(@Nullable final OnHintsFinished cb) {
        delegate.onFinishedConstruction(cb);
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public CompletableFuture<Bytes> signFuture(@NonNull final Bytes blockHash) {
        return delegate.signFuture(blockHash);
    }

    @Override
    public HintsHandlers handlers() {
        return delegate.handlers();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public void manageRosterAdoption(
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Roster previousRoster,
            @NonNull final Roster adoptedRoster,
            @NonNull final Bytes adoptedRosterHash,
            final boolean forceHandoff) {
        delegate.manageRosterAdoption(hintsStore, previousRoster, adoptedRoster, adoptedRosterHash, forceHandoff);
    }

    @Override
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Instant now,
            @NonNull final TssConfig tssConfig,
            final boolean currentPlatformStatus) {
        delegate.reconcile(activeRosters, hintsStore, now, tssConfig, currentPlatformStatus);
    }

    @Override
    public void executeCrsWork(
            @NonNull final WritableHintsStore hintsStore, @NonNull final Instant now, final boolean isActive) {
        delegate.executeCrsWork(hintsStore, now, isActive);
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        delegate.registerSchemas(registry);
    }

    @Override
    public long schemeId() {
        return delegate.schemeId();
    }

    @Override
    public Bytes verificationKey() {
        return delegate.verificationKey();
    }
}
