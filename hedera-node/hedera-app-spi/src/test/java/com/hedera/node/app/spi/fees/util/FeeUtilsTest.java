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
    void feeResultToFeesConvertsCorrectly() {
        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(2);
        when(rate.getCentEquiv()).thenReturn(1);

        FeeResult feeResult = new FeeResult(30, 10, 2);
        Fees fees = FeeUtils.feeResultToFees(feeResult, rate);

        assertEquals(20, fees.nodeFee(), "Node fee should be converted correctly");
        assertEquals(40, fees.networkFee(), "Network fee should be converted correctly");
        assertEquals(60, fees.serviceFee(), "Service fee should be converted correctly");
    }

    @Test
    void tinycentsToTinybarsHandlesOverflow() {
        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(Integer.MAX_VALUE);
        when(rate.getCentEquiv()).thenReturn(1);

        try (MockedStatic<FeeBuilder> fb = mockStatic(FeeBuilder.class)) {
            fb.when(() -> FeeBuilder.getTinybarsFromTinyCents(rate, Long.MAX_VALUE))
                    .thenReturn(999L);

            long result = FeeUtils.tinycentsToTinybars(Long.MAX_VALUE, rate);
            assertEquals(999L, result, "Should delegate to FeeBuilder on overflow");
        }
    }

    @Test
    void tinycentsToTinybarsRegularCalculation() {
        ExchangeRate rate = mock(ExchangeRate.class);
        when(rate.getHbarEquiv()).thenReturn(5);
        when(rate.getCentEquiv()).thenReturn(2);

        long result = FeeUtils.tinycentsToTinybars(10, rate);
        assertEquals(25, result, "Regular calculation: (10 * 5) / 2 = 25");
    }

    @Test
    void scaleToSubunitsIdentityAtDefaultDecimals() {
        // When subunitsPerWholeUnit == DEFAULT_SUBUNITS_PER_HBAR, should return input unchanged
        assertEquals(12_345L, scaleToSubunits(12_345L, DEFAULT_SUBUNITS_PER_HBAR), "Positive value unchanged");
        assertEquals(0L, scaleToSubunits(0L, DEFAULT_SUBUNITS_PER_HBAR), "Zero unchanged");
        assertEquals(-500L, scaleToSubunits(-500L, DEFAULT_SUBUNITS_PER_HBAR), "Negative value unchanged");
    }

    @Test
    void scaleToSubunitsScalesUpForMoreDecimals() {
        // decimals=18 → subunitsPerWholeUnit = 10^18
        final long subunits18 = 1_000_000_000_000_000_000L;
        // 1 default tinybar (10^-8 HBAR) = 10^10 subunits at decimals=18
        assertEquals(10_000_000_000L, scaleToSubunits(1L, subunits18), "1 tinybar scales to 10^10 at decimals=18");
        // 100 default tinybars = 10^12 subunits at decimals=18
        assertEquals(
                1_000_000_000_000L, scaleToSubunits(100L, subunits18), "100 tinybars scales to 10^12 at decimals=18");
    }

    @Test
    void scaleToSubunitsScalesDownForFewerDecimals() {
        // decimals=6 → subunitsPerWholeUnit = 10^6
        final long subunits6 = 1_000_000L;
        // 100 default tinybars (at decimals=8) = 1 subunit at decimals=6
        assertEquals(1L, scaleToSubunits(100L, subunits6), "100 tinybars becomes 1 subunit at decimals=6");
        // 50 default tinybars rounds down to 0 at decimals=6
        assertEquals(0L, scaleToSubunits(50L, subunits6), "50 tinybars rounds down to 0 at decimals=6");
    }

    @Test
    void scaleToSubunitsZeroDecimalsCollapsesToWholeUnits() {
        // decimals=0 → subunitsPerWholeUnit = 1
        // 100_000_000 default tinybars (= 1 HBAR) = 1 subunit at decimals=0
        assertEquals(1L, scaleToSubunits(DEFAULT_SUBUNITS_PER_HBAR, 1L), "1 HBAR becomes 1 at decimals=0");
        // Less than 1 HBAR rounds down to 0
        assertEquals(0L, scaleToSubunits(99_999_999L, 1L), "Less than 1 HBAR rounds to 0 at decimals=0");
    }

    @Test
    void scaleToSubunitsHandlesLargeValuesWithoutOverflow() {
        // decimals=18 with a large fee should use BigInteger and not overflow
        final long subunits18 = 1_000_000_000_000_000_000L;
        final long largeFee = 50_000_000L; // 0.5 HBAR in default tinybars
        // 50_000_000 * 10^18 / 10^8 = 50_000_000 * 10^10 = 5 * 10^17
        assertEquals(
                500_000_000_000_000_000L,
                scaleToSubunits(largeFee, subunits18),
                "Large fee at decimals=18 should not overflow");
    }
}
