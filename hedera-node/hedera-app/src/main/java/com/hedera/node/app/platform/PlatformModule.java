// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.platform;

import com.hedera.node.app.annotations.CommonExecutor;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.quiescence.TxPipelineTracker;
import com.hedera.node.app.state.listeners.FatalIssListenerImpl;
import com.hedera.node.app.state.listeners.ReconnectListener;
import com.hedera.node.app.state.listeners.WriteStateToDiskListener;
import com.hedera.node.config.data.QuiescenceConfig;
import com.swirlds.platform.listeners.ReconnectCompleteListener;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteListener;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.Charset;
import java.time.InstantSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import javax.inject.Singleton;
import org.hiero.base.crypto.Signer;

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

    @Provides
    @Singleton
    static QuiescenceController provideQuiescenceController(
            @NonNull final BootstrapConfigProviderImpl configProvider,
            @NonNull final TxPipelineTracker txPipelineTracker,
            @NonNull final InstantSource instantSource) {
        return new QuiescenceController(
                configProvider.configuration().getConfigData(QuiescenceConfig.class),
                instantSource,
                txPipelineTracker::estimateTxPipelineCount);
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
