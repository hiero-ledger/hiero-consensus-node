// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.hints.impl.HintsServiceImpl;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

public class FakeHintsService implements HintsService {
    private final HintsService delegate;
    private final HintsLibraryImpl operations = new HintsLibraryImpl();
    private final Queue<Runnable> pendingHintsSubmissions = new ArrayDeque<>();

    public FakeHintsService(@NonNull final AppContext appContext, @NonNull final Configuration bootstrapConfig) {
        delegate = new HintsServiceImpl(
                new NoOpMetrics(), pendingHintsSubmissions::offer, appContext, operations, bootstrapConfig);
    }

    @Override
    public @NonNull Bytes activeVerificationKeyOrThrow() {
        return delegate.activeVerificationKeyOrThrow();
    }

    @Override
    public void initSigningForNextScheme(@NonNull final ReadableHintsStore hintsStore) {
        delegate.initSigningForNextScheme(hintsStore);
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
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Instant now,
            @NonNull final TssConfig tssConfig) {
        delegate.reconcile(activeRosters, hintsStore, now, tssConfig);
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public HintsHandlers handlers() {
        return delegate.handlers();
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        delegate.registerSchemas(registry);
    }
}
