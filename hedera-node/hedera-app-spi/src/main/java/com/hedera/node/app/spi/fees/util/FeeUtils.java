// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees.util;

import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;

import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.spi.fees.Fees;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import org.hiero.hapi.fees.FeeResult;

public class FeeUtils {

    private static long tinycentsToTinybars(final long amount, final ExchangeRate rate) {
        final var hbarEquiv = rate.getHbarEquiv();
        if (productWouldOverflow(amount, hbarEquiv)) {
            return FeeBuilder.getTinybarsFromTinyCents(rate, amount);
        }
        return amount * hbarEquiv / rate.getCentEquiv();
    }

    public static Fees feeResultToFees(FeeResult feeResult, ExchangeRate rate) {
        return new Fees(
                tinycentsToTinybars(feeResult.node, rate),
                tinycentsToTinybars(feeResult.network, rate),
                tinycentsToTinybars(feeResult.service, rate));
    }

    public static FeeResult feesToFeeResult(Fees fees, ExchangeRate rate) {
        final var feeResult = new FeeResult();
        feeResult.addNodeFee("Node fee", 1, FeeBuilder.getTinybarsFromTinyCents(rate, fees.nodeFee()));
        feeResult.addNetworkFee("Network fee", 1, FeeBuilder.getTinybarsFromTinyCents(rate, fees.networkFee()));
        feeResult.addServiceFee("Service fee", 1, FeeBuilder.getTinybarsFromTinyCents(rate, fees.serviceFee()));
        return feeResult;
    }
}
