// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.history.handlers.HistoryProofKeyPublicationHandler;
import com.hedera.node.app.history.handlers.HistoryProofSignatureHandler;
import com.hedera.node.app.history.handlers.HistoryProofVoteHandler;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NodeInfo;
import com.swirlds.config.api.Configuration;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Module
public interface HistoryModule {
    @Provides
    @Singleton
    static Supplier<NodeInfo> provideSelfNodeInfoSupplier(@NonNull final AppContext appContext) {
        return requireNonNull(appContext).selfNodeInfoSupplier();
    }

    @Binds
    @Singleton
    ProofKeysAccessor bindProofKeyAccessor(@NonNull ProofKeysAccessorImpl proofKeysAccessorImpl);

    @Provides
    @Singleton
    static HistoryHandlers provideHistoryHandlers(
            @NonNull final HistoryProofSignatureHandler historyProofSignatureHandler,
            @NonNull final HistoryProofKeyPublicationHandler historyProofKeyPublicationHandler,
            @NonNull final HistoryProofVoteHandler historyProofVoteHandler) {
        return new HistoryHandlers(
                historyProofSignatureHandler, historyProofKeyPublicationHandler, historyProofVoteHandler);
    }

    @Provides
    @Singleton
    static Supplier<Configuration> provideConfigSupplier(@NonNull final AppContext appContext) {
        return requireNonNull(appContext).configSupplier();
    }
}
