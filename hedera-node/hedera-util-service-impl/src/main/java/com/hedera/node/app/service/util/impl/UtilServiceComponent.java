// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl;

import com.hedera.node.app.service.util.impl.cache.TransactionParser;
import com.hedera.node.app.service.util.impl.handlers.UtilHandlers;
import com.hedera.node.app.spi.AppContext;
import com.swirlds.config.api.Configuration;
import dagger.BindsInstance;
import dagger.Component;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Singleton
@Component(modules = UtilModule.class)
public interface UtilServiceComponent {
    @Component.Factory
    interface Factory {
        UtilServiceComponent create(
                @BindsInstance AppContext appContext, @BindsInstance TransactionParser transactionParser);
    }

    UtilHandlers handlers();

    @Deprecated
    Supplier<Configuration> configSupplier();
}
