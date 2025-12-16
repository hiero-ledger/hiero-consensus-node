// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.hedera.node.app.records.impl.producers.formats.SelfNodeAccountIdManagerImpl;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.app.state.recordcache.DeduplicationCacheImpl;
import com.hedera.node.app.state.recordcache.RecordCacheImpl;
import com.hedera.node.config.ConfigProvider;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Module
public interface HederaStateInjectionModule {
    @Binds
    RecordCache provideRecordCache(RecordCacheImpl cache);

    @Binds
    HederaRecordCache provideHederaRecordCache(RecordCacheImpl cache);

    @Binds
    DeduplicationCache provideDeduplicationCache(DeduplicationCacheImpl cache);

    @Provides
    @Singleton
    static WorkingStateAccessor provideWorkingStateAccessor() {
        return new WorkingStateAccessor();
    }

    @Provides
    @Singleton
    static SelfNodeAccountIdManager selfNodeAccountIdManager(
            @NonNull final ConfigProvider configProvider, @NonNull final NetworkInfo networkInfo) {
        return new SelfNodeAccountIdManagerImpl(configProvider, networkInfo);
    }

    @Provides
    @Singleton
    static BlockProvenStateAccessor provideBlockProvenStateAccessor() {
        return new BlockProvenStateAccessor();
    }

    @Provides
    @Singleton
    static BlockProvenSnapshotProvider provideBlockProvenSnapshotProvider(
            @NonNull final BlockProvenStateAccessor accessor) {
        return accessor;
    }

    @Provides
    @Singleton
    static Supplier<MerkleNodeState> provideBlockProvenStateSupplier(@NonNull final BlockProvenStateAccessor accessor) {
        return () -> accessor.latestState().orElse(null);
    }

    /**
     * Provides a Supplier of State for modules that need access to the latest working state
     * without directly depending on hedera-app module (e.g., CLPR service).
     *
     * @param accessor the WorkingStateAccessor that holds the current state
     * @return a Supplier that provides the current State when called
     */
    @Provides
    static Supplier<State> provideStateSupplier(@NonNull final WorkingStateAccessor accessor) {
        return accessor::getState;
    }
}
