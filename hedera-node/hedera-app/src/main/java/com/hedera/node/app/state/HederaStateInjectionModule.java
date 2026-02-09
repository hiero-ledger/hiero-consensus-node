// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.app.state.recordcache.DeduplicationCacheImpl;
import com.hedera.node.app.state.recordcache.RecordCacheImpl;
import com.swirlds.state.BinaryState;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
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
    static BlockProvenStateAccessor provideBlockProvenStateAccessor(
            @NonNull final StateLifecycleManager stateLifecycleManager) {
        return new BlockProvenStateAccessor(stateLifecycleManager);
    }

    @Provides
    @Singleton
    static BlockProvenSnapshotProvider provideBlockProvenSnapshotProvider(
            @NonNull final BlockProvenStateAccessor accessor) {
        return accessor;
    }

    @Provides
    @Singleton
    static Supplier<BinaryState> provideBlockProvenStateSupplier(@NonNull final BlockProvenStateAccessor accessor) {
        return () -> accessor.latestState()
                .filter(s -> s instanceof BinaryState)
                .map(s -> (BinaryState) s)
                .orElse(null);
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
