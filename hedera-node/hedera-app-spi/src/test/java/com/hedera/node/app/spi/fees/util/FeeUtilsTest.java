// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.spi.fees.Fees;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import org.hiero.hapi.fees.FeeResult;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class FeeUtilsTest {

    @Test
    void feeResultToFees_convertsCorrectly() {
        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(2);
        when(rate.getCentEquiv()).thenReturn(1);

        FeeResult feeResult = new FeeResult(30, 10, 2);
        Fees fees = FeeUtils.feeResultToFees(feeResult, rate);

        assertEquals(20, fees.nodeFee());
        assertEquals(40, fees.networkFee());
        assertEquals(60, fees.serviceFee());
    }

    @Test
    void tinycentsToTinybars_handlesOverflow() {
        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(Integer.MAX_VALUE);
        when(rate.getCentEquiv()).thenReturn(1);

        try (MockedStatic<FeeBuilder> fb = mockStatic(FeeBuilder.class)) {
            fb.when(() -> FeeBuilder.getTinybarsFromTinyCents(rate, Long.MAX_VALUE))
                    .thenReturn(999L);

            long result = FeeUtils.tinycentsToTinybars(Long.MAX_VALUE, rate);
            assertEquals(999L, result);
        }
    }

    @Test
    void tinycentsToTinybars_regularCalculation() {
        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(5);
        when(rate.getCentEquiv()).thenReturn(2);

        long result = FeeUtils.tinycentsToTinybars(10, rate);
        assertEquals(25, result); // (10 * 5) / 2 = 25
    }
}
