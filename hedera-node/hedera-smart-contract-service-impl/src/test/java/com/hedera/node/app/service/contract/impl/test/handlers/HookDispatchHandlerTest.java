// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.hooks.HookDispatchTransactionBody;
import com.hedera.hapi.node.hooks.HookExecution;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.handlers.HookDispatchHandler;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.HooksConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HookDispatchHandlerTest extends ContractHandlerTestBase {
    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private HookDispatchStreamBuilder recordBuilder;

    @Mock
    private HandleContext.SavepointStack savepointStack;

    @Mock
    protected WritableEvmHookStore evmHookStore;

    @Mock
    protected AttributeValidator attributeValidator;

    @Mock
    private EvmHookState hook;

    @Mock
    private TransactionComponent.Factory factory;

    @Mock
    private GasCalculator gasCalculator;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ContractServiceComponent contractServiceComponent;

    private Configuration config = HederaTestConfigBuilder.create()
            .withValue("hooks.hooksEnabled", true)
            .getOrCreateConfig();

    private HookDispatchHandler subject;

    @BeforeEach
    void setUp() {
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableEvmHookStore.class)).willReturn(evmHookStore);
        lenient().when(handleContext.attributeValidator()).thenReturn(attributeValidator);
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.savepointStack()).willReturn(savepointStack);
        given(savepointStack.getBaseBuilder(HookDispatchStreamBuilder.class)).willReturn(recordBuilder);
        lenient()
                .when(gasCalculator.transactionIntrinsicGasCost(
                        org.apache.tuweni.bytes.Bytes.wrap(new byte[0]), false, 0L))
                .thenReturn((long) config.getConfigData(HooksConfig.class).lambdaIntrinsicGasCost());
        subject = new HookDispatchHandler(() -> factory, gasCalculator, contractServiceComponent);
    }

    @Test
    void creationWorks() {
        given(handleContext.body()).willReturn(hookDispatchWithCreation());

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(evmHookStore).createEvmHook(any());
    }

    @Test
    void creationFailsIfHookExists() {
        given(handleContext.body()).willReturn(hookDispatchWithCreation());
        given(evmHookStore.getEvmHook(any())).willReturn(hook);

        final var response = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.HOOK_ID_IN_USE, response.getStatus());
    }

    @Test
    void deletionWorks() {
        given(handleContext.body()).willReturn(hookDispatchWithDeletion());
        given(handleContext.savepointStack()).willReturn(savepointStack);
        given(savepointStack.getBaseBuilder(HookDispatchStreamBuilder.class)).willReturn(recordBuilder);
        given(evmHookStore.getEvmHook(any())).willReturn(hook);
        given(hook.nextHookId()).willReturn(2L);
        assertDoesNotThrow(() -> subject.handle(handleContext));

        verify(recordBuilder).nextHookId(2L);
        verify(evmHookStore).remove(HookId.newBuilder().hookId(1L).build());
    }

    @Test
    void deletionFailsWhenHookNotFound() {
        given(handleContext.body()).willReturn(hookDispatchWithDeletion());
        given(handleContext.savepointStack()).willReturn(savepointStack);
        given(savepointStack.getBaseBuilder(HookDispatchStreamBuilder.class)).willReturn(recordBuilder);

        final var response = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.HOOK_NOT_FOUND, response.getStatus());
    }

    private TransactionBody hookDispatchWithExecution() {
        final TransactionID transactionID = TransactionID.newBuilder()
                .accountID(payer)
                .transactionValidStart(consensusTimestamp)
                .build();
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .hookDispatch(HookDispatchTransactionBody.newBuilder()
                        .execution(HookExecution.newBuilder().build())
                        .build())
                .build();
    }

    private TransactionBody hookDispatchWithCreation() {
        final TransactionID transactionID = TransactionID.newBuilder()
                .accountID(payer)
                .transactionValidStart(consensusTimestamp)
                .build();
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .hookDispatch(HookDispatchTransactionBody.newBuilder()
                        .creation(HookCreation.newBuilder()
                                .nextHookId(2L)
                                .details(HookCreationDetails.newBuilder()
                                        .hookId(1L)
                                        .adminKey(adminKey)
                                        .build())
                                .entityId(HookEntityId.newBuilder()
                                        .accountId(payer)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private TransactionBody hookDispatchWithDeletion() {
        final TransactionID transactionID = TransactionID.newBuilder()
                .accountID(payer)
                .transactionValidStart(consensusTimestamp)
                .build();
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .hookDispatch(HookDispatchTransactionBody.newBuilder()
                        .hookIdToDelete(HookId.newBuilder().hookId(1L).build())
                        .build())
                .build();
    }
}
