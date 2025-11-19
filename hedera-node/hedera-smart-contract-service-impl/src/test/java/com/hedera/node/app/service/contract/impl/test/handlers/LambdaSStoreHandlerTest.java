// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.hooks.LambdaMappingEntries;
import com.hedera.hapi.node.hooks.LambdaMappingEntry;
import com.hedera.hapi.node.hooks.LambdaSStoreTransactionBody;
import com.hedera.hapi.node.hooks.LambdaStorageSlot;
import com.hedera.hapi.node.hooks.LambdaStorageUpdate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.handlers.LambdaSStoreHandler;
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
public class LambdaSStoreHandlerTest {
    @Mock
    private FeeContext feeContext;

    @Mock
    private FeeCalculatorFactory feeCalculatorFactory;

    @Mock
    private FeeCalculator feeCalculator;

    @Test
    void returnsFixedCostOnUnexpectedException() {
        // Invalid hook id, will throw
        final var op = LambdaSStoreTransactionBody.newBuilder()
                .storageUpdates(List.of(
                        LambdaStorageUpdate.newBuilder()
                                .storageSlot(LambdaStorageSlot.DEFAULT)
                                .build(),
                        LambdaStorageUpdate.newBuilder()
                                .mappingEntries(LambdaMappingEntries.newBuilder()
                                        .entries(List.of(LambdaMappingEntry.DEFAULT, LambdaMappingEntry.DEFAULT))
                                        .build())
                                .build()))
                .build();
        final var tx = TransactionBody.newBuilder().lambdaSstore(op).build();
        given(feeContext.body()).willReturn(tx);
        given(feeContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).willReturn(feeCalculator);
        given(feeCalculator.addGas(3 * LambdaSStoreHandler.NONZERO_INTO_NONZERO_GAS_COST))
                .willReturn(feeCalculator);
        final var fees = new Fees(1, 2, 3);
        given(feeCalculator.calculate()).willReturn(fees);

        final var subject = new LambdaSStoreHandler();

        assertSame(fees, subject.calculateFees(feeContext));
    }
}
