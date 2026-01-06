// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.hapi.node.base.AccountID;

/**
 * An interface for tracking node fees during transaction processing.
 * <p>
 * This is used to update an in-memory map of node fees for each transaction,
 * which is then written to state at block boundaries for efficiency.
 * <p>
 * When {@code nodesConfig.feeCollectionAccountEnabled()} is true, node fees are
 * accumulated in a fee collection account rather than being paid directly to node accounts.
 * This accumulator maintains an in-memory map of how much each node should receive when
 * the fees are eventually distributed at staking period boundaries.
 * <p>
 * The accumulator supports both accumulating fees (during charging) and dissipating fees
 * (during refunds) to maintain accurate fee accounting.
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
