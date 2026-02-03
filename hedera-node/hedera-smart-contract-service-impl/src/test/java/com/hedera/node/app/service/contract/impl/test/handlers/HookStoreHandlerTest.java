// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.hooks.EvmHookMappingEntries;
import com.hedera.hapi.node.hooks.EvmHookMappingEntry;
import com.hedera.hapi.node.hooks.EvmHookStorageSlot;
import com.hedera.hapi.node.hooks.EvmHookStorageUpdate;
import com.hedera.hapi.node.hooks.HookStoreTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.handlers.HookStoreHandler;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fees.SimpleFeeContextUtil;
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
    private FeeCalculatorFactory feeCalculatorFactory;

    @Mock
    private FeeCalculator feeCalculator;

    @Test
    void returnsFixedCostOnUnexpectedException() {
        // Invalid hook id, will throw
        final var op = HookStoreTransactionBody.newBuilder()
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
        final long tinycentPerGas = 852L;
        final var tx = TransactionBody.newBuilder().hookStore(op).build();
        given(feeContext.body()).willReturn(tx);
        given(feeContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).willReturn(feeCalculator);
        given(feeContext.getGasPriceInTinycents()).willReturn(tinycentPerGas);
        given(feeCalculator.addGas((3 * TINYCENTS_PER_UPDATE + tinycentPerGas - 1) / tinycentPerGas))
                .willReturn(feeCalculator);
        final var fees = new Fees(1, 2, 3);
        given(feeCalculator.calculate()).willReturn(fees);

        final var subject = new HookStoreHandler();

        assertSame(fees, subject.calculateFees(feeContext));
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
        subject.accumulateServiceFee(tx, SimpleFeeContextUtil.fromFeeContext(feeContext), feeResult, feeSchedule);
        Assertions.assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(1000000000L);
    }
}
