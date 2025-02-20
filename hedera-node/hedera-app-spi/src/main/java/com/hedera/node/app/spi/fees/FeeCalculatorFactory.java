// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.hapi.node.base.SubType;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A factory for creating {@link FeeCalculator} instances.
 */
public interface FeeCalculatorFactory {

    /**
     * Get a calculator for calculating fees for the current transaction, and its {@link SubType}. Most transactions
     * just use {@link SubType#DEFAULT}, but some (such as crypto transfer) need to be more specific.
     *
     * @param subType The {@link SubType} of the transaction.
     * @return The {@link FeeCalculator} to use.
     */
    @NonNull
    FeeCalculator feeCalculator(@NonNull final SubType subType);
}
