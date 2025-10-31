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
 * Utility class for Simple Fees query handler operations.
 */
public final class SimpleFeeUtil {

    private SimpleFeeUtil() {}

    /**
     * Determines whether simple fees should be used for query fee calculation.
     * Simple fees use the new FeeResult-based system (tinycents) instead of the legacy
     * Fees-based system (tinybars).
     *
     * @param queryContext the query context containing configuration
     * @return true if simple fees are enabled in configuration, false otherwise
     */
    public static boolean shouldUseSimpleFees(@NonNull final QueryContext queryContext) {
        return queryContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled();
    }

    /**
     * Converts a FeeResult (in tinycents) to Fees (in tinybars) using the provided exchange rate.
     * The conversion applies the exchange rate to each fee component (node, network, service)
     * individually, handling potential overflow scenarios.
     *
     * @param feeResult the fee result in tinycents to convert
     * @param rate the exchange rate to use for conversion (hbar equivalent to cent equivalent)
     * @return the converted fees in tinybars
     */
    @NonNull
    public static Fees convertFeeResultToFees(@NonNull final FeeResult feeResult, @NonNull final ExchangeRate rate) {
        return new Fees(
                tinycentsToTinybars(feeResult.node, rate),
                tinycentsToTinybars(feeResult.network, rate),
                tinycentsToTinybars(feeResult.service, rate));
    }

    private static long tinycentsToTinybars(final long amount, final ExchangeRate rate) {
        final var hbarEquiv = rate.hbarEquiv();
        if (CommonUtils.productWouldOverflow(amount, hbarEquiv)) {
            return FeeBuilder.getTinybarsFromTinyCents(CommonPbjConverters.fromPbj(rate), amount);
        }
        return amount * hbarEquiv / rate.centEquiv();
    }
}
