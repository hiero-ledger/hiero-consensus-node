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

    @Test
    void tinycentsToTinybars_saturatesOnNonPositiveCentEquiv() {
        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(1);
        when(rate.getCentEquiv()).thenReturn(0);

        // A zero centEquiv would divide by zero; the conversion saturates instead of throwing.
        assertEquals(Long.MAX_VALUE, FeeUtils.tinycentsToTinybars(100L, rate));
    }

    @Test
    void tinycentsToTinybars_saturatesOnNonPositiveHbarEquiv() {
        ExchangeRate zeroHbarRate = mock(ExchangeRate.class);
        when(zeroHbarRate.getHbarEquiv()).thenReturn(0);
        when(zeroHbarRate.getCentEquiv()).thenReturn(120);
        // A zero hbarEquiv would make the fee free; saturate so a degenerate rate is unpayable, not free.
        assertEquals(Long.MAX_VALUE, FeeUtils.tinycentsToTinybars(100L, zeroHbarRate));

        ExchangeRate negativeHbarRate = mock(ExchangeRate.class);
        when(negativeHbarRate.getHbarEquiv()).thenReturn(-1);
        when(negativeHbarRate.getCentEquiv()).thenReturn(120);
        assertEquals(Long.MAX_VALUE, FeeUtils.tinycentsToTinybars(100L, negativeHbarRate));
    }

    @Test
    void feeResultToFeesTotalSaturatesOnDegenerateRate() {
        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(1);
        when(rate.getCentEquiv()).thenReturn(0);

        // End-to-end: each component conversion saturates on the degenerate rate, and the saturating total
        // clamps to Long.MAX_VALUE instead of overflowing Math.addExact in Fees.totalFee().
        FeeResult feeResult = new FeeResult(30, 10, 2);
        Fees fees = FeeUtils.feeResultToFees(feeResult, rate);

        assertEquals(Long.MAX_VALUE, fees.nodeFee());
        assertEquals(Long.MAX_VALUE, fees.networkFee());
        assertEquals(Long.MAX_VALUE, fees.serviceFee());
        assertEquals(Long.MAX_VALUE, fees.totalFee());
    }
}
