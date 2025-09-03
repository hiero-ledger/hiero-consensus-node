// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hints.handlers.CrsPublicationHandler;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.handlers.HintsKeyPublicationHandler;
import com.hedera.node.app.hints.handlers.HintsPartialSignatureHandler;
import com.hedera.node.app.hints.handlers.HintsPreprocessingVoteHandler;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Module
public interface HintsModule {
    @Provides
    @Singleton
    static Supplier<NodeInfo> provideSelfNodeInfoSupplier(@NonNull final AppContext appContext) {
        return requireNonNull(appContext).selfNodeInfoSupplier();
    }

    @Binds
    @Singleton
    HintsKeyAccessor bindHintsKeyAccessor(@NonNull HintsKeyAccessorImpl hintsKeyAccessorImpl);

    @Provides
    @Singleton
    static Supplier<Configuration> provideConfigSupplier(@NonNull final AppContext appContext) {
        return requireNonNull(appContext).configSupplier();
    }

    @Provides
    @Singleton
    static ConcurrentMap<Bytes, HintsContext.Signing> providePendingSignatures() {
        return new ConcurrentHashMap<>();
    }

    @Provides
    @Singleton
    static HintsHandlers provideHintsHandlers(
            @NonNull final HintsKeyPublicationHandler keyPublicationHandler,
            @NonNull final HintsPreprocessingVoteHandler preprocessingVoteHandler,
            @NonNull final HintsPartialSignatureHandler partialSignatureHandler,
            @NonNull final CrsPublicationHandler crsPublicationHandler) {
        return new HintsHandlers(
                keyPublicationHandler, preprocessingVoteHandler, partialSignatureHandler, crsPublicationHandler);
    }
}
