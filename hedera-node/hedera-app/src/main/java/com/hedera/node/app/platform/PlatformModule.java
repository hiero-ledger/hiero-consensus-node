/*
 * Copyright (C) 2020-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.platform;

import com.hedera.node.app.annotations.CommonExecutor;
import com.hedera.node.app.state.listeners.FatalIssListenerImpl;
import com.hedera.node.app.state.listeners.ReconnectListener;
import com.hedera.node.app.state.listeners.WriteStateToDiskListener;
import com.swirlds.common.stream.Signer;
import com.swirlds.platform.listeners.ReconnectCompleteListener;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteListener;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Module
public interface PlatformModule {
    @Provides
    @Singleton
    static Signer signer(@NonNull final Platform platform) {
        return platform::sign;
    }

    @Provides
    @Singleton
    @CommonExecutor
    static ExecutorService provideCommonExecutor() {
        return ForkJoinPool.commonPool();
    }

    @Provides
    @Singleton
    static Supplier<Charset> provideNativeCharset() {
        return Charset::defaultCharset;
    }

    @Binds
    @Singleton
    ReconnectCompleteListener bindReconnectListener(ReconnectListener reconnectListener);

    @Binds
    @Singleton
    StateWriteToDiskCompleteListener bindStateWrittenToDiskListener(WriteStateToDiskListener writeStateToDiskListener);

    @Binds
    @Singleton
    AsyncFatalIssListener bindFatalIssListener(FatalIssListenerImpl listener);
}
