// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.calc;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.FEE_DIVISOR_FACTOR;

import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.hapi.utils.fee.FeeObject;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A specialized fee calculator that throws an exception if any step of the fee calculation
 * overflows.
 *
 * <p>(Because all prices and usage estimates are known to be non-negative, checking for an overflow
 * means just checking for a number less than zero.)
 */
@Singleton
public final class OverflowCheckingCalc {
    private static final String OVERFLOW_ERROR =
            "A fee calculation step overflowed; " + "the operation cannot be priced, and therefore cannot be performed";

    @Inject
    public OverflowCheckingCalc() {
        /* No-op */
    }

    /**
     * Returns the network, node, and services fees for an operation given four inputs.
     *
     * <p>The first input to the calculation is a resource usage estimate in the form of an instance
     * of {@link UsageAccumulator}. (See the Javadoc on hat class for a detailed description of
     * resource types.)
     *
     * <p>The second input is a {@code FeeData} instance that has the price of each resource in
     * units of 1/1000th of a tinycent.
     *
     * <p>The third input is the active exchange rate between ℏ and ¢ (equivalently, between tinybar
     * and tinycent); and the final input is a multiplier that is almost always one, except in cases
     * of extreme congestion pricing.
     *
     * @param usage the resources used by an operation
     * @param prices the prices of those resources, in units of 1/1000th of a tinycent
     * @param rate the exchange rate between ℏ and ¢
     * @param multiplier a scale factor determined by congestion pricing
     * @return fee object containing the node, network, and service fees
     * @throws IllegalArgumentException if any step of the calculation overflows
     */
    public FeeObject fees(
            final UsageAccumulator usage, final FeeData prices, final ExchangeRate rate, final long multiplier) {
        final long networkFeeTinycents = networkFeeInTinycents(usage, prices.getNetworkdata());
        final long nodeFeeTinycents = nodeFeeInTinycents(usage, prices.getNodedata());
        final long serviceFeeTinycents = serviceFeeInTinycents(usage, prices.getServicedata());

        final long unscaledNetworkFee = tinycentsToTinybars(networkFeeTinycents, rate);
        final long unscaledNodeFee = tinycentsToTinybars(nodeFeeTinycents, rate);
        final long unscaledServiceFee = tinycentsToTinybars(serviceFeeTinycents, rate);

        final long maxUnscaled = Long.MAX_VALUE / multiplier;
        if (unscaledNetworkFee > maxUnscaled || unscaledNodeFee > maxUnscaled || unscaledServiceFee > maxUnscaled) {
            throw new IllegalArgumentException(OVERFLOW_ERROR);
        }

        return new FeeObject(
                unscaledNodeFee * multiplier, unscaledNetworkFee * multiplier, unscaledServiceFee * multiplier);
    }

    public static long tinycentsToTinybars(final long amount, final ExchangeRate rate) {
        final var hbarEquiv = rate.getHbarEquiv();
        if (productWouldOverflow(amount, hbarEquiv)) {
            return FeeBuilder.getTinybarsFromTinyCents(rate, amount);
        }
        return amount * hbarEquiv / rate.getCentEquiv();
    }

    private long networkFeeInTinycents(final UsageAccumulator usage, final FeeComponents networkPrices) {
        final var nominal = safeAccumulateThree(
                networkPrices.getConstant(),
                usage.getUniversalBpt() * networkPrices.getBpt(),
                usage.getNetworkVpt() * networkPrices.getVpt(),
                usage.getNetworkRbh() * networkPrices.getRbh());
        return constrainedTinycentFee(nominal, networkPrices.getMin(), networkPrices.getMax());
    }

    private long nodeFeeInTinycents(final UsageAccumulator usage, final FeeComponents nodePrices) {
        final var nominal = safeAccumulateFour(
                nodePrices.getConstant(),
                usage.getUniversalBpt() * nodePrices.getBpt(),
                usage.getNodeBpr() * nodePrices.getBpr(),
                usage.getNodeSbpr() * nodePrices.getSbpr(),
                usage.getNodeVpt() * nodePrices.getVpt());
        return constrainedTinycentFee(nominal, nodePrices.getMin(), nodePrices.getMax());
    }

    private long serviceFeeInTinycents(final UsageAccumulator usage, final FeeComponents servicePrices) {
        final var nominal = safeAccumulateTwo(
                servicePrices.getConstant(),
                usage.getServiceRbh() * servicePrices.getRbh(),
                usage.getServiceSbh() * servicePrices.getSbh());
        return constrainedTinycentFee(nominal, servicePrices.getMin(), servicePrices.getMax());
    }

    /* Prices in file 0.0.111 are actually set in units of 1/1000th of a tinycent,
     * so here we constrain the nominal price by the max/min and then divide by
     * 1000 (the value of FEE_DIVISOR_FACTOR). */
    private long constrainedTinycentFee(long nominal, final long min, final long max) {
        if (nominal < min) {
            nominal = min;
        } else if (nominal > max) {
            nominal = max;
        }
        return ESTIMATOR_UTILS.nonDegenerateDiv(nominal, FEE_DIVISOR_FACTOR);
    }

    /* These verbose accumulators signatures are to avoid any performance hit from varargs */
    long safeAccumulateFour(final long base, final long a, final long b, final long c, final long d) {
        if (d < 0) {
            throw new IllegalArgumentException(OVERFLOW_ERROR);
        }
        var sum = safeAccumulateThree(base, a, b, c);
        sum += d;
        if (sum < 0) {
            throw new IllegalArgumentException(OVERFLOW_ERROR);
        }
        return sum;
    }

    long safeAccumulateThree(final long base, final long a, final long b, final long c) {
        if (c < 0) {
            throw new IllegalArgumentException(OVERFLOW_ERROR);
        }
        var sum = safeAccumulateTwo(base, a, b);
        sum += c;
        if (sum < 0) {
            throw new IllegalArgumentException(OVERFLOW_ERROR);
        }
        return sum;
    }

    long safeAccumulateTwo(long base, final long a, final long b) {
        if (base < 0 || a < 0 || b < 0) {
            throw new IllegalArgumentException(OVERFLOW_ERROR);
        }
        base += a;
        if (base < 0) {
            throw new IllegalArgumentException(OVERFLOW_ERROR);
        }
        base += b;
        if (base < 0) {
            throw new IllegalArgumentException(OVERFLOW_ERROR);
        }
        return base;
    }
}
