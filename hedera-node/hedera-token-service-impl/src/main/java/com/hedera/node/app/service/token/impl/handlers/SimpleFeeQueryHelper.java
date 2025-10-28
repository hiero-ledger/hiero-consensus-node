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
public final class SimpleFeeQueryHelper {

    private SimpleFeeQueryHelper() {}

    public static boolean shouldUseSimpleFees(@NonNull final QueryContext queryContext) {
        return queryContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled();
    }

    @NonNull
    public static Fees convertFeeResultToFees(@NonNull final FeeResult feeResult, @NonNull final ExchangeRate rate) {
        return new Fees(
                tinycentsToTinybars(feeResult.node, rate),
                tinycentsToTinybars(feeResult.network, rate),
                tinycentsToTinybars(feeResult.service, rate));
    }

    private static long tinycentsToTinybars(final long amount, final ExchangeRate rate) {
        final var hbarEquiv = rate.hbarEquiv();
        if (productWouldOverflow(amount, hbarEquiv)) {
            return FeeBuilder.getTinybarsFromTinyCents(CommonPbjConverters.fromPbj(rate), amount);
        }
        return amount * hbarEquiv / rate.centEquiv();
    }

    private static boolean productWouldOverflow(final long a, final int b) {
        return CommonUtils.productWouldOverflow(a, b);
    }
}
