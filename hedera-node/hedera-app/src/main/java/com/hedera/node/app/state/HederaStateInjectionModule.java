// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.state.recordcache.DeduplicationCacheImpl;
import com.hedera.node.app.state.recordcache.RecordCacheImpl;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
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
}
