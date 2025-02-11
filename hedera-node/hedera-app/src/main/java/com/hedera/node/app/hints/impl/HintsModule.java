/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hints.handlers.CrsPublicationHandler;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.handlers.HintsKeyPublicationHandler;
import com.hedera.node.app.hints.handlers.HintsPartialSignatureHandler;
import com.hedera.node.app.hints.handlers.HintsPreprocessingVoteHandler;
import com.hedera.node.app.spi.AppContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.info.NodeInfo;
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
