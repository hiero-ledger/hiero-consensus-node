// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.base.FeeData;

/**
 * Summarizes the base resource prices and active congestion multiplier for a
 * {@link com.hedera.hapi.node.base.HederaFunctionality}.
 *
 * @param basePrices the base resource prices
 * @param congestionMultiplier the active congestion multiplier
 */
public record FunctionalityResourcePrices(FeeData basePrices, long congestionMultiplier) {
    /**
     * The all-zero prices of resources that have been pre-paid via a query header CryptoTransfer.
     */
    public static final FunctionalityResourcePrices PREPAID_RESOURCE_PRICES =
            new FunctionalityResourcePrices(FeeData.DEFAULT, 1);
}
