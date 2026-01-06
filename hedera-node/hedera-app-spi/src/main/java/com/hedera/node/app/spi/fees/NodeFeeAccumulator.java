// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.hapi.node.base.AccountID;

/**
 * An interface for accumulating and deaccumulating node fees during transaction processing.
 * <p>
 * This is used to update an in-memory map of node fees for each transaction,
 * which is then written to state at block boundaries for efficiency.
 */
public interface NodeFeeAccumulator {
    /**
     * A no-op accumulator that does nothing. It is only used in tests and in StandaloneDispatchFactory
     */
    NodeFeeAccumulator NOOP = new NodeFeeAccumulator() {
        @Override
        public void accumulate(AccountID nodeAccountId, long fees) {
            // no-op
        }

        @Override
        public void deaccumulate(AccountID nodeAccountId, long fees) {
            // no-op
        }
    };

    /**
     * Accumulates node fees for each transaction processed. This will update an in-memory map of node fees
     * for each transaction, which is then written to state at block boundaries for efficiency.
     *
     * @param nodeAccountId the node account id
     * @param fees the fees to accumulate
     */
    void accumulate(AccountID nodeAccountId, long fees);

    /**
     * Deaccumulates (refunds) node fees for a transaction. This is called when a transaction is rolled back
     * or fees need to be refunded. This will update the in-memory map of node fees.
     *
     * @param nodeAccountId the node account id
     * @param fees the fees to deaccumulate (should be a positive value)
     */
    void deaccumulate(AccountID nodeAccountId, long fees);
}
