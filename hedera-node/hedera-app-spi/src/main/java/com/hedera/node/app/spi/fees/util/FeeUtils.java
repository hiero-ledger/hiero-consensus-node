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
                tinycentsToTinybars(feeResult.getNodeTotalTinycents(), rate),
                tinycentsToTinybars(feeResult.getNetworkTotalTinycents(), rate),
                tinycentsToTinybars(feeResult.getServiceTotalTinycents(), rate),
                feeResult.getHighVolumeMultiplier());
    }

    public static long tinycentsToTinybars(final long amount, final ExchangeRate rate) {
        final var hbarEquiv = rate.getHbarEquiv();
        if (productWouldOverflow(amount, hbarEquiv)) {
            return FeeBuilder.getTinybarsFromTinyCents(rate, amount);
        }
        return amount * hbarEquiv / rate.getCentEquiv();
    }
}
