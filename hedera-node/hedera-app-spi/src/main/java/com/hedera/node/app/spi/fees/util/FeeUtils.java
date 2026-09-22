// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees.util;

import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;

import com.hedera.node.app.hapi.utils.fee.FeeConstants;
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
        final var centEquiv = rate.getCentEquiv();
        // A non-positive centEquiv would divide by zero, and a non-positive hbarEquiv would make the fee
        // free or negative; saturate to Long.MAX_VALUE instead of throwing, so a degenerate rate yields an
        // unpayable fee rather than halting fee conversion identically on every node.
        if (centEquiv <= 0 || hbarEquiv <= 0) {
            return Long.MAX_VALUE;
        }
        if (productWouldOverflow(amount, hbarEquiv)) {
            return FeeConstants.getTinybarsFromTinyCents(rate, amount);
        }
        return amount * hbarEquiv / centEquiv;
    }
}
