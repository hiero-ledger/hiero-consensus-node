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

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.spi.AppContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import dagger.BindsInstance;
import dagger.Component;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Singleton
@Component(modules = HistoryModule.class)
public interface HistoryServiceComponent {
    @Component.Factory
    interface Factory {
        HistoryServiceComponent create(
                @BindsInstance HistoryLibrary library,
                @BindsInstance HistoryLibraryCodec codec,
                @BindsInstance AppContext appContext,
                @BindsInstance Executor executor,
                @BindsInstance Metrics metrics,
                @BindsInstance Consumer<HistoryProof> proofConsumer);
    }

    HistoryHandlers handlers();

    ProofControllers controllers();

    @Deprecated
    Supplier<Configuration> configSupplier();
}
