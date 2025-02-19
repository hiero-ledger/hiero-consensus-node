/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.history.handlers.HistoryProofKeyPublicationHandler;
import com.hedera.node.app.history.handlers.HistoryProofSignatureHandler;
import com.hedera.node.app.history.handlers.HistoryProofVoteHandler;
import com.hedera.node.app.spi.AppContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.info.NodeInfo;
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
