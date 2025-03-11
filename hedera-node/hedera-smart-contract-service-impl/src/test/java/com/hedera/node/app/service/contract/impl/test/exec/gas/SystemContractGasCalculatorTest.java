// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.gas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.CanonicalDispatchPrices;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import java.util.function.ToLongBiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemContractGasCalculatorTest {
    @Mock
    private TinybarValues tinybarValues;

    @Mock
    private ToLongBiFunction<TransactionBody, AccountID> feeCalculator;

    @Mock
    private CanonicalDispatchPrices dispatchPrices;

    private SystemContractGasCalculator subject;

    @BeforeEach
    void setUp() {
        subject = new SystemContractGasCalculator(tinybarValues, dispatchPrices, feeCalculator);
    }

    @Test
    void returnsMinimumGasCostForViews() {
        assertEquals(1198L, subject.viewGasRequirement());
    }

    @Test
    void computesCanonicalDispatchType() {
        given(dispatchPrices.canonicalPriceInTinycents(DispatchType.APPROVE)).willReturn(123L);
        assertEquals(123L, subject.canonicalPriceInTinycents(DispatchType.APPROVE));
    }

    @Test
    void computesCanonicalDispatch() {
        given(feeCalculator.applyAsLong(TransactionBody.DEFAULT, AccountID.DEFAULT))
                .willReturn(123L);
        assertEquals(123L, subject.feeCalculatorPriceInTinyBars(TransactionBody.DEFAULT, AccountID.DEFAULT));
    }

    @Test
    void computesGasCostInTinybars() {
        given(tinybarValues.childTransactionTinybarGasPrice()).willReturn(2L);
        assertEquals(6L, subject.gasCostInTinybars(3L));
    }

    @Test
    void delegatesTopLevelGasPrice() {
        given(tinybarValues.topLevelTinybarGasPriceFullPrecision()).willReturn(123L);
        assertEquals(123L, subject.topLevelGasPriceInTinyBars());
    }
}
