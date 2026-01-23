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

        FeeResult feeResult = new FeeResult();
        feeResult.node = 10;
        feeResult.network = 20;
        feeResult.service = 30;

        Fees fees = FeeUtils.feeResultToFees(feeResult, rate);

        assertEquals(20, fees.nodeFee());
        assertEquals(40, fees.networkFee());
        assertEquals(60, fees.serviceFee());
    }

    @Test
    void feesToFeeResult_convertsCorrectly() {
        ExchangeRate rate = mock(ExchangeRate.class);
        Fees fees = new Fees(100, 200, 300);

        try (MockedStatic<FeeBuilder> fb = mockStatic(FeeBuilder.class)) {
            fb.when(() -> FeeBuilder.getTinybarsFromTinyCents(rate, 100)).thenReturn(10L);
            fb.when(() -> FeeBuilder.getTinybarsFromTinyCents(rate, 200)).thenReturn(20L);
            fb.when(() -> FeeBuilder.getTinybarsFromTinyCents(rate, 300)).thenReturn(30L);

            FeeResult result = FeeUtils.feesToFeeResult(fees, rate);

            assertEquals(10L, result.node);
            assertEquals(20L, result.network);
            assertEquals(30L, result.service);
        }
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
    void feeResultToFeesWithMultiplier_appliesMultiplierCorrectly() {
        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(2);
        when(rate.getCentEquiv()).thenReturn(1);

        FeeResult feeResult = new FeeResult();
        feeResult.node = 10;
        feeResult.network = 20;
        feeResult.service = 30;

        long multiplier = 7L;
        Fees fees = FeeUtils.feeResultToFeesWithMultiplier(feeResult, rate, multiplier);

        // Without multiplier: node=20, network=40, service=60
        // With 7x multiplier: node=140, network=280, service=420
        assertEquals(140, fees.nodeFee());
        assertEquals(280, fees.networkFee());
        assertEquals(420, fees.serviceFee());
    }

    @Test
    void feeResultToFeesWithMultiplier_handlesNoMultiplier() {
        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(2);
        when(rate.getCentEquiv()).thenReturn(1);

        FeeResult feeResult = new FeeResult();
        feeResult.node = 10;
        feeResult.network = 20;
        feeResult.service = 30;

        long multiplier = 1L;
        Fees fees = FeeUtils.feeResultToFeesWithMultiplier(feeResult, rate, multiplier);

        // With multiplier=1, should be same as without multiplier
        assertEquals(20, fees.nodeFee());
        assertEquals(40, fees.networkFee());
        assertEquals(60, fees.serviceFee());
    }

    @Test
    void feeResultToFeesWithMultiplier_clampsOnOverflow() {
        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(1);
        when(rate.getCentEquiv()).thenReturn(1);

        FeeResult feeResult = new FeeResult();
        feeResult.node = Long.MAX_VALUE / 2;
        feeResult.network = Long.MAX_VALUE / 2;
        feeResult.service = Long.MAX_VALUE / 2;

        long multiplier = 10L; // This will cause overflow
        Fees fees = FeeUtils.feeResultToFeesWithMultiplier(feeResult, rate, multiplier);

        // All values should be clamped to Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, fees.nodeFee());
        assertEquals(Long.MAX_VALUE, fees.networkFee());
        assertEquals(Long.MAX_VALUE, fees.serviceFee());
    }
}
