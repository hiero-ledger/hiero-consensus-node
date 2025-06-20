// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import com.hedera.node.app.service.contract.impl.annotations.InitialState;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.workflows.HandleContext;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;

@Module
public interface TransactionInitialStateModule {
    @Provides
    @InitialState
    @TransactionScope
    static ReadableFileStore provideInitialFileStore(@NonNull final HandleContext context) {
        return context.storeFactory().readableStore(ReadableFileStore.class);
    }

    @Provides
    @InitialState
    @TransactionScope
    static ReadableAccountStore provideInitialAccountStore(@NonNull final HandleContext context) {
        return context.storeFactory().readableStore(ReadableAccountStore.class);
    }

    @Provides
    @InitialState
    @TransactionScope
    static TokenServiceApi provideInitialTokenServiceApi(@NonNull final HandleContext context) {
        return context.storeFactory().serviceApi(TokenServiceApi.class);
    }
}
