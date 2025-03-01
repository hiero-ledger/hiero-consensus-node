// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.hapi.node.base.HederaFunctionality.EVM_HOOK_DISPATCH;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.service.contract.impl.annotations.InitialState;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.contract.impl.state.ReadableEvmHookStore;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.workflows.HandleContext;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

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
    @Nullable
    @InitialState
    @TransactionScope
    static ReadableEvmHookStore provideInitialEvmHookStore(
            @NonNull final HandleContext context, @NonNull final HederaFunctionality function) {
        return function == EVM_HOOK_DISPATCH ? context.storeFactory().readableStore(ReadableEvmHookStore.class) : null;
    }

    @Provides
    @InitialState
    @TransactionScope
    static TokenServiceApi provideInitialTokenServiceApi(@NonNull final HandleContext context) {
        return context.storeFactory().serviceApi(TokenServiceApi.class);
    }
}
