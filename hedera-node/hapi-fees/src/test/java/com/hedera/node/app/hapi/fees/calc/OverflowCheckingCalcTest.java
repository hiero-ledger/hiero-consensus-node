// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.calc;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.HRS_DIVISOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import org.junit.jupiter.api.Test;

class OverflowCheckingCalcTest {
    private static final int rateTinybarComponent = 1001;
    private static final int rateTinycentComponent = 1000;
    private static final ExchangeRate someRate = ExchangeRate.newBuilder()
            .hbarEquiv(rateTinybarComponent)
            .centEquiv(rateTinycentComponent)
            .build();
    private static final OverflowCheckingCalc subject = new OverflowCheckingCalc();

    @Test
    void throwsOnMultiplierOverflow() {
        final var usage = new UsageAccumulator();
        copyData(mockUsage, usage);

        assertThrows(IllegalArgumentException.class, () -> subject.fees(usage, mockPrices, mockRate, Long.MAX_VALUE));
    }

    @Test
    void converterCanFallbackToBigDecimal() {
        final var highFee = Long.MAX_VALUE / rateTinycentComponent;
        final var expectedTinybarFee = FeeBuilder.getTinybarsFromTinyCents(someRate, highFee);

        final long computedTinybarFee = OverflowCheckingCalc.tinycentsToTinybars(highFee, someRate);

        assertEquals(expectedTinybarFee, computedTinybarFee);
    }

    @Test
    void matchesLegacyCalc() {
        final var legacyFees = FeeBuilder.getFeeObject(mockPrices, mockUsage, mockRate, multiplier);
        final var usage = new UsageAccumulator();
        copyData(mockUsage, usage);

        final var refactoredFees = subject.fees(usage, mockPrices, mockRate, multiplier);

        assertEquals(legacyFees.nodeFee(), refactoredFees.nodeFee());
        assertEquals(legacyFees.networkFee(), refactoredFees.networkFee());
        assertEquals(legacyFees.serviceFee(), refactoredFees.serviceFee());
    }

    @Test
    void ceilingIsEnforced() {
        final var cappedFees = FeeBuilder.getFeeObject(mockLowCeilPrices, mockUsage, mockRate, multiplier);
        final var usage = new UsageAccumulator();
        copyData(mockUsage, usage);

        final var refactoredFees = subject.fees(usage, mockLowCeilPrices, mockRate, multiplier);

        assertEquals(cappedFees.nodeFee(), refactoredFees.nodeFee());
        assertEquals(cappedFees.networkFee(), refactoredFees.networkFee());
        assertEquals(cappedFees.serviceFee(), refactoredFees.serviceFee());
    }

    @Test
    void floorIsEnforced() {
        final var cappedFees = FeeBuilder.getFeeObject(mockHighFloorPrices, mockUsage, mockRate, multiplier);
        final var usage = new UsageAccumulator();
        copyData(mockUsage, usage);

        final var refactoredFees = subject.fees(usage, mockHighFloorPrices, mockRate, multiplier);

        assertEquals(cappedFees.nodeFee(), refactoredFees.nodeFee());
        assertEquals(cappedFees.networkFee(), refactoredFees.networkFee());
        assertEquals(cappedFees.serviceFee(), refactoredFees.serviceFee());
    }

    @Test
    void safeAccumulateTwoWorks() {
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateTwo(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateTwo(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateTwo(1, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateTwo(1, Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateTwo(1, 1, Long.MAX_VALUE));

        assertEquals(3, subject.safeAccumulateTwo(1, 1, 1));
    }

    @Test
    void safeAccumulateThreeWorks() {
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateThree(-1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateThree(1, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateThree(1, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateThree(1, 1, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateThree(1, Long.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateThree(1, 1, Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateThree(1, 1, 1, Long.MAX_VALUE));

        assertEquals(4, subject.safeAccumulateThree(1, 1, 1, 1));
    }

    @Test
    void safeAccumulateFourWorks() {
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateFour(-1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateFour(1, -1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateFour(1, 1, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateFour(1, 1, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateFour(1, 1, 1, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateFour(1, Long.MAX_VALUE, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateFour(1, 1, Long.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateFour(1, 1, 1, Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> subject.safeAccumulateFour(1, 1, 1, 1, Long.MAX_VALUE));

        assertEquals(5, subject.safeAccumulateFour(1, 1, 1, 1, 1));
    }

    private static final long multiplier = 2L;
    private static final long veryHighFloorFee = Long.MAX_VALUE / 2;
    private static final FeeComponents mockLowCeilFees = FeeComponents.newBuilder()
            .max(1234567L)
            .constant(1_234_567L)
            .bpr(1_000_000L)
            .bpt(2_000_000L)
            .rbh(3_000_000L)
            .sbh(4_000_000L)
            .build();
    private static final FeeComponents mockHighFloorFees = FeeComponents.newBuilder()
            .min(veryHighFloorFee)
            .constant(1_234_567L)
            .bpr(1_000_000L)
            .bpt(2_000_000L)
            .rbh(3_000_000L)
            .sbh(4_000_000L)
            .build();
    private static final FeeComponents mockFees = FeeComponents.newBuilder()
            .max(Long.MAX_VALUE)
            .constant(1_234_567L)
            .bpr(1_000_000L)
            .bpt(2_000_000L)
            .rbh(3_000_000L)
            .sbh(4_000_000L)
            .build();
    private static final ExchangeRate mockRate =
            ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(120).build();

    private static final FeeData mockPrices = FeeData.newBuilder()
            .networkdata(mockFees)
            .nodedata(mockFees)
            .servicedata(mockFees)
            .build();
    private static final FeeData mockLowCeilPrices = FeeData.newBuilder()
            .networkdata(mockLowCeilFees)
            .nodedata(mockLowCeilFees)
            .servicedata(mockLowCeilFees)
            .build();
    private static final FeeData mockHighFloorPrices = FeeData.newBuilder()
            .networkdata(mockHighFloorFees)
            .nodedata(mockHighFloorFees)
            .servicedata(mockHighFloorFees)
            .build();

    private static final long one = 1;
    private static final long bpt = 2;
    private static final long vpt = 3;
    private static final long rbh = 4;
    private static final long sbh = 5;
    private static final long bpr = 8;
    private static final long sbpr = 9;
    private static final long network_rbh = 10;
    private static final FeeComponents mockUsageVector = FeeComponents.newBuilder()
            .constant(one)
            .bpt(bpt)
            .vpt(vpt)
            .rbh(rbh)
            .sbh(sbh)
            .bpr(bpr)
            .sbpr(sbpr)
            .build();
    private static final FeeData mockUsage =
            ESTIMATOR_UTILS.withDefaultTxnPartitioning(mockUsageVector, SubType.DEFAULT, network_rbh, 3);

    private static final void copyData(final FeeData feeData, final UsageAccumulator into) {
        into.setNumPayerKeys(feeData.nodedata().vpt());
        into.addVpt(feeData.networkdata().vpt());
        into.addBpt(feeData.networkdata().bpt());
        into.addBpr(feeData.nodedata().bpr());
        into.addSbpr(feeData.nodedata().sbpr());
        into.addNetworkRbs(feeData.networkdata().rbh() * HRS_DIVISOR);
        into.addRbs(feeData.servicedata().rbh() * HRS_DIVISOR);
        into.addSbs(feeData.servicedata().sbh() * HRS_DIVISOR);
    }
}
