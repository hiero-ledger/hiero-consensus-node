// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees.util;

import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;

import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.spi.fees.Fees;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import org.hiero.hapi.fees.FeeResult;

/**
 * Utility class for converting between different fee representations.
 * Provides methods to convert fees between `FeeResult` and `Fees` objects,
 * as well as utility methods for handling tinycents to tinybars conversions.
 */
public class FeeUtils {

    private FeeUtils() {
        // util class
    }

    /**
     * Converts a `FeeResult` object to a `Fees` object using the provided exchange rate.
     *
     * @param feeResult The `FeeResult` object containing node, network, and service fees in tinycents.
     * @param rate The `ExchangeRate` object used to convert tinycents to tinybars.
     * @return A `Fees` object containing the converted fees in tinybars.
     */
    public static Fees feeResultToFees(FeeResult feeResult, ExchangeRate rate) {
        return new Fees(
                tinycentsToTinybars(feeResult.node, rate),
                tinycentsToTinybars(feeResult.network, rate),
                tinycentsToTinybars(feeResult.service, rate));
    }

    /**
     * Converts a `Fees` object to a `FeeResult` object using the provided exchange rate.
     *
     * @param fees The `Fees` object containing node, network, and service fees in tinybars.
     * @param rate The `ExchangeRate` object used to convert tinybars to tinycents.
     * @return A `FeeResult` object containing the converted fees in tinycents.
     */
    public static FeeResult feesToFeeResult(Fees fees, ExchangeRate rate) {
        final var feeResult = new FeeResult();
        feeResult.addNodeBase(FeeBuilder.getTinybarsFromTinyCents(rate, fees.nodeFee()));
        feeResult.addNetworkFee(1, FeeBuilder.getTinybarsFromTinyCents(rate, fees.networkFee()));
        feeResult.addServiceBase(FeeBuilder.getTinybarsFromTinyCents(rate, fees.serviceFee()));
        return feeResult;
    }

    public static long tinycentsToTinybars(final long amount, final ExchangeRate rate) {
        final var hbarEquiv = rate.getHbarEquiv();
        if (productWouldOverflow(amount, hbarEquiv)) {
            return FeeBuilder.getTinybarsFromTinyCents(rate, amount);
        }
        return amount * hbarEquiv / rate.getCentEquiv();
    }
}
