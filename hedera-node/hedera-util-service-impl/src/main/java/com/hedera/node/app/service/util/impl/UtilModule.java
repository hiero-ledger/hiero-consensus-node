// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.util.impl.handlers.AtomicBatchHandler;
import com.hedera.node.app.service.util.impl.handlers.UtilHandlers;
import com.hedera.node.app.service.util.impl.handlers.UtilPrngHandler;
import com.hedera.node.app.spi.AppContext;
import com.swirlds.config.api.Configuration;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Module
public class UtilModule {

    @Provides
    @Singleton
    static Supplier<Configuration> provideConfigSupplier(@NonNull final AppContext appContext) {
        return requireNonNull(appContext).configSupplier();
    }

    @Provides
    @Singleton
    static UtilHandlers provideUtilHandler(
            @NonNull final UtilPrngHandler provideUtilPrngHandler, @NonNull AtomicBatchHandler atomicBatchHandler) {
        return new UtilHandlers(provideUtilPrngHandler, atomicBatchHandler);
    }
}
