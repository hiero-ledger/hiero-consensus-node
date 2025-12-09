// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.hapi.node.base.AccountID;

/**
 * A functional interface for accumulating node fees during transaction processing.
 * <p>
 * This is used to update an in-memory map of node fees for each transaction,
 * which is then written to state at block boundaries for efficiency.
 */
@FunctionalInterface
public interface NodeFeeAccumulator {
    /**
     * A no-op accumulator that does nothing.
     */
    NodeFeeAccumulator NOOP = (nodeAccountNumber, fees) -> {};

    /**
     * Accumulates fees for a node account.
     *
     * @param nodeAccountId the node account id
     * @param fees the fees to accumulate
     */
    void accumulate(AccountID nodeAccountId, long fees);
}
