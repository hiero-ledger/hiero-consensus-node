// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.workflows.TransactionInfo;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Interface which purpose is to do the work of tracking network utilization (and its impact on
 * congestion pricing).
 */
public interface NetworkUtilizationManager {

    /**
     * Updates the throttle usage and congestion pricing using the given transaction.
     *
     * @param txnInfo       - the transaction to use for updating the network utilization.
     * @param consensusTime - the consensus time of the transaction.
     * @param state         - the state of the node.
     * @return whether the transaction was throttled
     */
    boolean trackTxn(
            @NonNull final TransactionInfo txnInfo, @NonNull final Instant consensusTime, @NonNull final State state);

    /**
     * Updates the throttle usage and congestion pricing for cases where the transaction is not valid, but we want to track the fee payments related to it.
     *
     * @param consensusNow - the consensus time of the transaction.
     * @param state - the state of the node.
     */
    void trackFeePayments(@NonNull final Instant consensusNow, @NonNull final State state);

    /**
     * Indicates whether the last transaction was throttled by gas.
     *
     * @return true if the last transaction was throttled by gas; false otherwise.
     */
    boolean wasLastTxnGasThrottled();

    /**
     * Leaks the gas amount previously reserved for the given transaction.
     *
     * @param txnInfo the transaction to leak the gas for
     * @param value the amount of gas to leak
     */
    void leakUnusedGasPreviouslyReserved(@NonNull final TransactionInfo txnInfo, final long value);

    /**
     * Returns the available ops duration capacity for the execution at a given consensus time.
     * Takes into account the amount leaked from the bucket up to the provided consensus time.
     */
    long availableOpsDurationCapacity(@NonNull Instant consensusTime);

    /**
     * Consumes a given amount of ops duration units from the throttle's capacity.
     * Takes into account the amount leaked from the bucket up to the provided consensus time.
     * If the amount to consume is greater than the available amount then consumes
     * the available amount and returns (does not fail).
     */
    void consumeOpsDurationThrottleCapacity(long opsDurationUnitsToConsume, @NonNull Instant consensusTime);

    /**
     * Verifies if the throttle in this operation context has enough capacity to handle the given number of the
     * given function at the given time. (The time matters because we want to consider how much
     * will have leaked between now and that time.)
     *
     * @param n the number of the given function
     * @param function the function
     * @return true if the system should throttle the given number of the given function
     * at the instant for which throttling should be calculated
     */
    boolean shouldThrottleNOfUnscaled(int n, @NonNull HederaFunctionality function, @NonNull Instant consensusTime);
}
