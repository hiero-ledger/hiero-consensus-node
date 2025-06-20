// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.node.app.spi.workflows.FunctionalityResourcePrices;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A calculator for determining the resource prices for a given {@link HederaFunctionality} and {@link SubType}.
 */
public interface ResourcePriceCalculator {

    /**
     * Returns the Hedera resource prices (in thousandths of a tinycent) for the given {@link SubType} of
     * the given {@link HederaFunctionality}. The contract service needs this information to determine both the
     * gas price and the cost of storing logs (a function of the {@code rbh} price, which may itself vary by
     * contract operation type).
     *
     * @param functionality the {@link HederaFunctionality} of interest
     * @param subType the {@link SubType} of interest
     * @return the corresponding Hedera resource prices
     */
    @NonNull
    FunctionalityResourcePrices resourcePricesFor(
            @NonNull final HederaFunctionality functionality, @NonNull final SubType subType);
}
