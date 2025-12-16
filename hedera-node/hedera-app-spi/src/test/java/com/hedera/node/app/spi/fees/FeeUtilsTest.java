// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.node.app.spi.fees.util.FeeUtils;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import org.hiero.hapi.fees.FeeResult;
import org.junit.jupiter.api.Test;

public class FeeUtilsTest {

    @Test
    void testFeeResultToFees() {
        FeeResult feeResult = new FeeResult();
        feeResult.addNodeFee(1, 1000);
        feeResult.addNetworkFee(2000);
        feeResult.addServiceFee(1, 3000);

        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(2);
        when(rate.getCentEquiv()).thenReturn(1);

        Fees fees = FeeUtils.feeResultToFees(feeResult, rate);

        assertEquals(2000, fees.nodeFee());
        assertEquals(4000, fees.networkFee());
        assertEquals(6000, fees.serviceFee());
    }

    @Test
    void testFeesToFeeResult() {
        Fees fees = new Fees(1000, 2000, 3000);

        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(2);
        when(rate.getCentEquiv()).thenReturn(1);

        FeeResult feeResult = FeeUtils.feesToFeeResult(fees, rate);

        assertEquals(2000, feeResult.node);
        assertEquals(4000, feeResult.network);
        assertEquals(6000, feeResult.service);
    }
}
