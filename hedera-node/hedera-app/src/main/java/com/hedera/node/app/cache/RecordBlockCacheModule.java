// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.cache;

import com.hedera.node.app.records.impl.producers.BlockRecordWriterFactory;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;

/**
 * Dagger module for providing the {@link RecordBlockCache}.
 */
@Module
public interface RecordBlockCacheModule {
    @Provides
    @Singleton
    static RecordBlockCache provideRecordBlockCache(
            @NonNull final ConfigProvider configProvider,
            @NonNull NetworkInfo networkInfo,
            @NonNull BlockRecordWriterFactory blockRecordWriterFactory) {
        return new RecordBlockCache(configProvider, networkInfo, blockRecordWriterFactory);
    }
}
