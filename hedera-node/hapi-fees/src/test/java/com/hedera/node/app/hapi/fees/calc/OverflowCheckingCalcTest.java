// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.calc;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.utils.fee.FeeConstants.FEE_DIVISOR_FACTOR;
import static com.hedera.node.app.hapi.utils.fee.FeeConstants.HRS_DIVISOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.FeeConstants;
import com.hedera.node.app.hapi.utils.fee.FeeObject;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.SubType;
import org.junit.jupiter.api.Test;

class OverflowCheckingCalcTest {
    private static final int rateTinybarComponent = 1001;
    private static final int rateTinycentComponent = 1000;
    private static final ExchangeRate someRate = ExchangeRate.newBuilder()
            .setHbarEquiv(rateTinybarComponent)
            .setCentEquiv(rateTinycentComponent)
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
        final var expectedTinybarFee = FeeConstants.getTinybarsFromTinyCents(someRate, highFee);

        final long computedTinybarFee = OverflowCheckingCalc.tinycentsToTinybars(highFee, someRate);

        assertEquals(expectedTinybarFee, computedTinybarFee);
    }

    @Test
    void matchesLegacyCalc() {
        final var legacyFees = legacyFeeObject(mockPrices, mockUsage, mockRate, multiplier);
        final var usage = new UsageAccumulator();
        copyData(mockUsage, usage);

        final var refactoredFees = subject.fees(usage, mockPrices, mockRate, multiplier);

        assertEquals(legacyFees.nodeFee(), refactoredFees.nodeFee());
        assertEquals(legacyFees.networkFee(), refactoredFees.networkFee());
        assertEquals(legacyFees.serviceFee(), refactoredFees.serviceFee());
    }

    @Test
    void ceilingIsEnforced() {
        final var cappedFees = legacyFeeObject(mockLowCeilPrices, mockUsage, mockRate, multiplier);
        final var usage = new UsageAccumulator();
        copyData(mockUsage, usage);

        final var refactoredFees = subject.fees(usage, mockLowCeilPrices, mockRate, multiplier);

        assertEquals(cappedFees.nodeFee(), refactoredFees.nodeFee());
        assertEquals(cappedFees.networkFee(), refactoredFees.networkFee());
        assertEquals(cappedFees.serviceFee(), refactoredFees.serviceFee());
    }

    @Test
    void floorIsEnforced() {
        final var cappedFees = legacyFeeObject(mockHighFloorPrices, mockUsage, mockRate, multiplier);
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
            .setMax(1234567L)
            .setConstant(1_234_567L)
            .setBpr(1_000_000L)
            .setBpt(2_000_000L)
            .setRbh(3_000_000L)
            .setSbh(4_000_000L)
            .build();
    private static final FeeComponents mockHighFloorFees = FeeComponents.newBuilder()
            .setMin(veryHighFloorFee)
            .setConstant(1_234_567L)
            .setBpr(1_000_000L)
            .setBpt(2_000_000L)
            .setRbh(3_000_000L)
            .setSbh(4_000_000L)
            .build();
    private static final FeeComponents mockFees = FeeComponents.newBuilder()
            .setMax(Long.MAX_VALUE)
            .setConstant(1_234_567L)
            .setBpr(1_000_000L)
            .setBpt(2_000_000L)
            .setRbh(3_000_000L)
            .setSbh(4_000_000L)
            .build();
    private static final ExchangeRate mockRate =
            ExchangeRate.newBuilder().setHbarEquiv(1).setCentEquiv(120).build();

    private static final FeeData mockPrices = FeeData.newBuilder()
            .setNetworkdata(mockFees)
            .setNodedata(mockFees)
            .setServicedata(mockFees)
            .build();
    private static final FeeData mockLowCeilPrices = FeeData.newBuilder()
            .setNetworkdata(mockLowCeilFees)
            .setNodedata(mockLowCeilFees)
            .setServicedata(mockLowCeilFees)
            .build();
    private static final FeeData mockHighFloorPrices = FeeData.newBuilder()
            .setNetworkdata(mockHighFloorFees)
            .setNodedata(mockHighFloorFees)
            .setServicedata(mockHighFloorFees)
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
            .setConstant(one)
            .setBpt(bpt)
            .setVpt(vpt)
            .setRbh(rbh)
            .setSbh(sbh)
            .setBpr(bpr)
            .setSbpr(sbpr)
            .build();
    private static final FeeData mockUsage =
            ESTIMATOR_UTILS.withDefaultTxnPartitioning(mockUsageVector, SubType.DEFAULT, network_rbh, 3);

    /* Local reimplementation of the removed legacy FeeBuilder.getFeeObject, kept as an independent
     * oracle to cross-check OverflowCheckingCalc against the original pricing formula. */
    private static FeeObject legacyFeeObject(
            final FeeData feeData, final FeeData feeMatrices, final ExchangeRate exchangeRate, final long multiplier) {
        long networkFee = componentFeeInTinycents(feeData.getNetworkdata(), feeMatrices.getNetworkdata());
        long nodeFee = componentFeeInTinycents(feeData.getNodedata(), feeMatrices.getNodedata());
        long serviceFee = componentFeeInTinycents(feeData.getServicedata(), feeMatrices.getServicedata());
        networkFee = FeeConstants.getTinybarsFromTinyCents(exchangeRate, networkFee) * multiplier;
        nodeFee = FeeConstants.getTinybarsFromTinyCents(exchangeRate, nodeFee) * multiplier;
        serviceFee = FeeConstants.getTinybarsFromTinyCents(exchangeRate, serviceFee) * multiplier;
        return new FeeObject(nodeFee, networkFee, serviceFee);
    }

    private static long componentFeeInTinycents(final FeeComponents coeff, final FeeComponents metrics) {
        long total = coeff.getConstant() * metrics.getConstant()
                + coeff.getBpt() * metrics.getBpt()
                + coeff.getVpt() * metrics.getVpt()
                + coeff.getRbh() * metrics.getRbh()
                + coeff.getSbh() * metrics.getSbh()
                + coeff.getGas() * metrics.getGas()
                + coeff.getTv() * metrics.getTv()
                + coeff.getBpr() * metrics.getBpr()
                + coeff.getSbpr() * metrics.getSbpr();
        if (total < coeff.getMin()) {
            total = coeff.getMin();
        } else if (total > coeff.getMax()) {
            total = coeff.getMax();
        }
        return Math.max(total > 0 ? 1 : 0, total / FEE_DIVISOR_FACTOR);
    }

    private static final void copyData(final FeeData feeData, final UsageAccumulator into) {
        into.setNumPayerKeys(feeData.getNodedata().getVpt());
        into.addVpt(feeData.getNetworkdata().getVpt());
        into.addBpt(feeData.getNetworkdata().getBpt());
        into.addBpr(feeData.getNodedata().getBpr());
        into.addSbpr(feeData.getNodedata().getSbpr());
        into.addNetworkRbs(feeData.getNetworkdata().getRbh() * HRS_DIVISOR);
        into.addRbs(feeData.getServicedata().getRbh() * HRS_DIVISOR);
        into.addSbs(feeData.getServicedata().getSbh() * HRS_DIVISOR);
    }
}
