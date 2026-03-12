// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees.util;

import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;

import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.spi.fees.Fees;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import java.math.BigInteger;
import org.hiero.hapi.fees.FeeResult;

/**
 * Utility class for converting between different fee representations.
 * Provides methods to convert fees between `FeeResult` and `Fees` objects,
 * as well as utility methods for handling tinycents to tinybars conversions.
 */
public class FeeUtils {

    /**
     * The default number of subunits per whole HBAR (10^8 tinybars per HBAR),
     * corresponding to decimals=8.
     */
    public static final long DEFAULT_SUBUNITS_PER_HBAR = 100_000_000L;

    private FeeUtils() {
        // util class
    }

    /**
     * Scales a fee amount computed in default tinybars (10^-8 HBAR) to the
     * configured native coin subunits (10^-decimals HBAR).
     *
     * <p>When {@code subunitsPerWholeUnit == DEFAULT_SUBUNITS_PER_HBAR} (i.e. decimals=8),
     * this is a no-op and returns the input unchanged.
     *
     * @param defaultTinybars the fee amount in default tinybars (10^-8 HBAR)
     * @param subunitsPerWholeUnit the number of subunits per whole HBAR for the configured decimals
     * @return the fee amount scaled to the configured denomination
     */
    public static long scaleToSubunits(final long defaultTinybars, final long subunitsPerWholeUnit) {
        if (subunitsPerWholeUnit == DEFAULT_SUBUNITS_PER_HBAR) {
            return defaultTinybars;
        }
        return BigInteger.valueOf(defaultTinybars)
                .multiply(BigInteger.valueOf(subunitsPerWholeUnit))
                .divide(BigInteger.valueOf(DEFAULT_SUBUNITS_PER_HBAR))
                .longValueExact();
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
