// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.hapi.node.base.AccountID;

/**
 * An interface for tracking node fees during transaction processing.
 * <p>
 * This is used to update an in-memory map of node fees for each transaction,
 * which is then written to state at block boundaries for efficiency.
 */
public interface NodeFeeAccumulator {
    /**
     * A no-op accumulator that does nothing. It is only used in tests and in StandaloneDispatchFactory.
     */
    NodeFeeAccumulator NOOP = new NodeFeeAccumulator() {
        @Override
        public void accumulate(AccountID nodeAccountId, long fees) {
            // No-op
        }

        @Override
        public void dissipate(AccountID nodeAccountId, long fees) {
            // No-op
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
     * Dissipates node fees when a refund is processed. This will update the in-memory map to reduce
     * the accumulated fees for the given node account. This is called when fees that were previously
     * accumulated need to be refunded (e.g., when {@code zeroHapiFees} is enabled for successful
     * Ethereum transactions).
     * <p>
     * If the dissipate would result in a negative balance for the node, the implementation should
     * handle this gracefully (e.g., by clamping to zero or logging a warning).
     *
     * @param nodeAccountId the node account id
     * @param fees the fees to dissipate
     */
    void dissipate(AccountID nodeAccountId, long fees);
}
