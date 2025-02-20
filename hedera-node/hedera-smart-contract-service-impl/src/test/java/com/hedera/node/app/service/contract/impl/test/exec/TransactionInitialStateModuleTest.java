// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.TransactionInitialStateModule;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionInitialStateModuleTest {
    @Mock
    private ReadableAccountStore readableAccountStore;

    @Mock
    private TokenServiceApi tokenServiceApi;

    @Mock
    private ReadableFileStore readableFileStore;

    @Mock
    private HandleContext context;

    @Mock
    private StoreFactory storeFactory;

    @Test
    void providesInitialState() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(storeFactory.readableStore(ReadableFileStore.class)).willReturn(readableFileStore);
        assertSame(tokenServiceApi, TransactionInitialStateModule.provideInitialTokenServiceApi(context));
        assertSame(readableAccountStore, TransactionInitialStateModule.provideInitialAccountStore(context));
        assertSame(readableFileStore, TransactionInitialStateModule.provideInitialFileStore(context));
    }
}
