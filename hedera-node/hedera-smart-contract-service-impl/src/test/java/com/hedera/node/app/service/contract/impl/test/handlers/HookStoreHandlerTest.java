// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.given;

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class HookStoreHandlerTest {
    @Mock
    private FeeContext feeContext;

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
        final var tx = TransactionBody.newBuilder().hookStore(op).build();
        given(feeContext.body()).willReturn(tx);
        given(feeContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).willReturn(feeCalculator);
        given(feeCalculator.addGas(3 * HookStoreHandler.NONZERO_INTO_NONZERO_GAS_COST))
                .willReturn(feeCalculator);
        final var fees = new Fees(1, 2, 3);
        given(feeCalculator.calculate()).willReturn(fees);

        final var subject = new HookStoreHandler();

        assertSame(fees, subject.calculateFees(feeContext));
    }
}
