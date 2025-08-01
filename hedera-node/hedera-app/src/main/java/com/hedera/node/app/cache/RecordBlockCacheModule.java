// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.cache;

import com.hedera.node.config.ConfigProvider;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;

@Module
public interface RecordBlockCacheModule {
    @Provides
    @Singleton
    static RecordBlockCache provideRecordBlockCache(
            @NonNull final ConfigProvider configProvider, @NonNull NetworkInfo networkInfo) {
        return new RecordBlockCache(configProvider, networkInfo);
    }
}
