// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.FeesConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;

/**
 * Utility class for Simple Fees (HIP-1261) query handler operations.
 * Provides common methods for checking if simple fees are enabled and converting
 * fee results to the legacy Fees format.
 */
public final class SimpleFeeQueryHelper {

    private SimpleFeeQueryHelper() {
        // Utility class, prevent instantiation
    }

    /**
     * Checks if simple fees are enabled in the configuration.
     *
     * @param queryContext the query context containing configuration
     * @return true if simple fees are enabled, false otherwise
     * @throws NullPointerException if {@code queryContext} is {@code null}
     */
    public static boolean shouldUseSimpleFees(@NonNull final QueryContext queryContext) {
        return queryContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled();
    }

    /**
     * Converts a FeeResult (in tinycents) to legacy Fees format (in tinybars).
     *
     * @param feeResult the fee result in tinycents (node, network, service)
     * @param rate the exchange rate for conversion from USD to HBAR
     * @return the fees in tinybars
     * @throws NullPointerException if {@code feeResult} or {@code rate} is {@code null}
     */
    @NonNull
    public static Fees convertFeeResultToFees(@NonNull final FeeResult feeResult, @NonNull final ExchangeRate rate) {
        return new Fees(
                tinycentsToTinybars(feeResult.node, rate),
                tinycentsToTinybars(feeResult.network, rate),
                tinycentsToTinybars(feeResult.service, rate));
    }

    /**
     * Converts an amount from tinycents to tinybars using the provided exchange rate.
     *
     * @param amount the amount in tinycents
     * @param rate the exchange rate
     * @return the amount in tinybars
     */
    private static long tinycentsToTinybars(final long amount, final ExchangeRate rate) {
        final var hbarEquiv = rate.hbarEquiv();
        if (productWouldOverflow(amount, hbarEquiv)) {
            return FeeBuilder.getTinybarsFromTinyCents(CommonPbjConverters.fromPbj(rate), amount);
        }
        return amount * hbarEquiv / rate.centEquiv();
    }

    /**
     * Checks if multiplying two numbers would result in overflow.
     *
     * @param a the first operand
     * @param b the second operand
     * @return true if the product would overflow, false otherwise
     */
    private static boolean productWouldOverflow(final long a, final int b) {
        return CommonUtils.productWouldOverflow(a, b);
    }
}
