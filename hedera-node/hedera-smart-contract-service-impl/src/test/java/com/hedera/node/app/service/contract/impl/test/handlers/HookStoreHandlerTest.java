// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.TOO_MANY_EVM_HOOK_STORAGE_UPDATES;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.hooks.EvmHookMappingEntries;
import com.hedera.hapi.node.hooks.EvmHookMappingEntry;
import com.hedera.hapi.node.hooks.EvmHookStorageSlot;
import com.hedera.hapi.node.hooks.EvmHookStorageUpdate;
import com.hedera.hapi.node.hooks.HookStoreTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.context.SimpleFeeContextImpl;
import com.hedera.node.app.service.contract.impl.handlers.HookStoreHandler;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeDefinition;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;
import org.hiero.hapi.support.fees.ServiceFeeSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class HookStoreHandlerTest {
    private static final long TINYCENTS_PER_UPDATE = 50_000_000L;

    @Mock
    private FeeContext feeContext;

    @Mock
    private FeeResult feeResult;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableEvmHookStore evmHookStore;

    @Mock
    private TokenServiceApi tokenServiceApi;

    private static final HookId HOOK_ID = HookId.newBuilder()
            .entityId(HookEntityId.newBuilder()
                    .accountId(AccountID.newBuilder().accountNum(1001).build())
                    .build())
            .hookId(1L)
            .build();

    @Test
    void mappingEntriesCountAgainstTheUpdateLimit() {
        // One update, but its four mapping entries write four slots
        final var updates = List.of(mappingUpdate(4));
        givenHandleOf(updates, 3);

        assertThatThrownBy(() -> new HookStoreHandler().handle(handleContext))
                .isInstanceOf(HandleException.class)
                .extracting(e -> ((HandleException) e).getStatus())
                .isEqualTo(TOO_MANY_EVM_HOOK_STORAGE_UPDATES);
        verify(evmHookStore, never()).updateStorage(any(HookId.class), any());
    }

    @Test
    void slotsAndMappingEntriesUpToTheLimitAreApplied() {
        // One single slot plus two mapping entries is exactly three slots
        final var updates = List.of(
                EvmHookStorageUpdate.newBuilder()
                        .storageSlot(EvmHookStorageSlot.DEFAULT)
                        .build(),
                mappingUpdate(2));
        givenHandleOf(updates, 3);
        given(evmHookStore.updateStorage(HOOK_ID, updates)).willReturn(3);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);

        new HookStoreHandler().handle(handleContext);

        verify(evmHookStore).updateStorage(HOOK_ID, updates);
        verify(tokenServiceApi).updateHookStorageSlots(HOOK_ID.entityIdOrThrow().accountIdOrThrow(), 3, false);
    }

    private void givenHandleOf(final List<EvmHookStorageUpdate> updates, final int maxHookStoreUpdates) {
        final var op = storeOp(updates);
        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().hookStore(op).build());
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableEvmHookStore.class)).willReturn(evmHookStore);
        given(handleContext.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("hooks.maxHookStoreUpdates", maxHookStoreUpdates)
                        .getOrCreateConfig());
    }

    private static HookStoreTransactionBody storeOp(final List<EvmHookStorageUpdate> updates) {
        return HookStoreTransactionBody.newBuilder()
                .hookId(HOOK_ID)
                .storageUpdates(updates)
                .build();
    }

    private static EvmHookStorageUpdate mappingUpdate(final int numEntries) {
        return EvmHookStorageUpdate.newBuilder()
                .mappingEntries(EvmHookMappingEntries.newBuilder()
                        .entries(Collections.nCopies(numEntries, EvmHookMappingEntry.DEFAULT))
                        .build())
                .build();
    }

    @Test
    void simpleFeeCalculatorSimplyScalesBaseByCount() {
        final var op = HookStoreTransactionBody.newBuilder()
                .hookId(HookId.DEFAULT)
                .storageUpdates(List.of(
                        EvmHookStorageUpdate.newBuilder()
                                .storageSlot(EvmHookStorageSlot.DEFAULT)
                                .build(),
                        EvmHookStorageUpdate.newBuilder()
                                .mappingEntries(EvmHookMappingEntries.newBuilder()
                                        .entries(List.of(EvmHookMappingEntry.DEFAULT, EvmHookMappingEntry.DEFAULT))
                                        .build())
                                .build()))
                .build();
        final var tx = TransactionBody.newBuilder().hookStore(op).build();
        final var hookFeeSchedule = ServiceFeeSchedule.newBuilder()
                .schedule(ServiceFeeDefinition.newBuilder()
                        .name(HederaFunctionality.HOOK_STORE)
                        .baseFee(0)
                        .extras(ExtraFeeReference.newBuilder()
                                .name(Extra.HOOK_SLOT_UPDATE)
                                .includedCount(1)
                                .build())
                        .build())
                .build();
        final var feeSchedule = FeeSchedule.newBuilder()
                .services(List.of(hookFeeSchedule))
                .extras(ExtraFeeDefinition.newBuilder()
                        .name(Extra.HOOK_SLOT_UPDATE)
                        .fee(500000000L)
                        .build())
                .build();
        final var subject = new HookStoreHandler.FeeCalculator();

        final var feeResult = new FeeResult();
        subject.accumulateServiceFee(tx, new SimpleFeeContextImpl(feeContext, null), feeResult, feeSchedule);
        Assertions.assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(1000000000L);
    }
}
