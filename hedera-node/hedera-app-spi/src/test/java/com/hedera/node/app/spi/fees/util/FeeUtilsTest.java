// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees.util;

import static com.hedera.node.app.spi.fees.util.FeeUtils.DEFAULT_SUBUNITS_PER_HBAR;
import static com.hedera.node.app.spi.fees.util.FeeUtils.scaleToSubunits;
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
    void scaleToSubunits_identityAtDefaultDecimals() {
        // When subunitsPerWholeUnit == DEFAULT_SUBUNITS_PER_HBAR, should return input unchanged
        assertEquals(12345L, scaleToSubunits(12345L, DEFAULT_SUBUNITS_PER_HBAR));
        assertEquals(0L, scaleToSubunits(0L, DEFAULT_SUBUNITS_PER_HBAR));
        assertEquals(-500L, scaleToSubunits(-500L, DEFAULT_SUBUNITS_PER_HBAR));
    }

    @Test
    void scaleToSubunits_scalesUpForMoreDecimals() {
        // decimals=18 → subunitsPerWholeUnit = 10^18
        final long subunits18 = 1_000_000_000_000_000_000L;
        // 1 default tinybar (10^-8 HBAR) = 10^10 subunits at decimals=18
        assertEquals(10_000_000_000L, scaleToSubunits(1L, subunits18));
        // 100 default tinybars = 10^12 subunits at decimals=18
        assertEquals(1_000_000_000_000L, scaleToSubunits(100L, subunits18));
    }

    @Test
    void scaleToSubunits_scalesDownForFewerDecimals() {
        // decimals=6 → subunitsPerWholeUnit = 10^6
        final long subunits6 = 1_000_000L;
        // 100 default tinybars (at decimals=8) = 1 subunit at decimals=6
        assertEquals(1L, scaleToSubunits(100L, subunits6));
        // 50 default tinybars rounds down to 0 at decimals=6
        assertEquals(0L, scaleToSubunits(50L, subunits6));
    }

    @Test
    void scaleToSubunits_zeroDecimalsCollapsesToWholeUnits() {
        // decimals=0 → subunitsPerWholeUnit = 1
        // 100_000_000 default tinybars (= 1 HBAR) = 1 subunit at decimals=0
        assertEquals(1L, scaleToSubunits(DEFAULT_SUBUNITS_PER_HBAR, 1L));
        // Less than 1 HBAR rounds down to 0
        assertEquals(0L, scaleToSubunits(99_999_999L, 1L));
    }

    @Test
    void scaleToSubunits_handlesLargeValuesWithoutOverflow() {
        // decimals=18 with a large fee should use BigInteger and not overflow
        final long subunits18 = 1_000_000_000_000_000_000L;
        final long largeFee = 50_000_000L; // 0.5 HBAR in default tinybars
        // 50_000_000 * 10^18 / 10^8 = 50_000_000 * 10^10 = 5 * 10^17
        assertEquals(500_000_000_000_000_000L, scaleToSubunits(largeFee, subunits18));
    }
}
