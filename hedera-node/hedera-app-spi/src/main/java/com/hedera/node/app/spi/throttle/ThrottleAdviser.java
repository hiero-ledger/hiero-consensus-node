// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.throttle;

import com.hedera.hapi.node.base.HederaFunctionality;
import edu.umd.cs.findbugs.annotations.NonNull;

public interface ThrottleAdviser {

    /**
     * The consensus throttle rejections seen by a {@link ConsensusThrottleRejectionCapture}, from least to most severe
     * for work that must be handled in order.
     */
    enum ConsensusThrottleRejection {
        /** No consensus throttle rejected work. */
        NONE,
        /**
         * Consensus throttles rejected work only because it needs more capacity than they can ever hold; the
         * rejection repeats at any time, regardless of the load on the network.
         */
        BEYOND_CAPACITY,
        /**
         * A consensus throttle rejected work for lack of free capacity; the work may be accepted at another time,
         * once the load on the network drops.
         */
        TEMPORARILY_BUSY
    }

    /**
     * Captures the consensus throttle rejections recorded in this operation context, including its child dispatches,
     * from when it is opened by {@link #captureConsensusThrottleRejections()} until it is closed.
     */
    interface ConsensusThrottleRejectionCapture extends AutoCloseable {
        /**
         * Returns the most severe consensus throttle rejection captured so far; a temporarily busy rejection outranks
         * one beyond capacity, since the captured work may still be accepted at another time.
         *
         * @return the most severe captured rejection
         */
        @NonNull
        ConsensusThrottleRejection rejection();

        /**
         * Stops capturing rejections.
         */
        @Override
        void close();
    }

    /**
     * Verifies if the throttle in this operation context has enough capacity to handle the given number of the
     * given function at the given time. (The time matters because we want to consider how much
     * will have leaked between now and that time.)
     *
     * <p>Callers reject the work when the answer is to throttle, so that answer is also recorded as a consensus
     * throttle rejection, as by {@link #recordConsensusThrottleRejection(boolean)}.
     *
     * @param n the number of the given function
     * @param function the function
     * @return true if the system should throttle the given number of the given function
     * at the instant for which throttling should be calculated
     */
    boolean shouldThrottleNOfUnscaled(int n, HederaFunctionality function);

    /**
     * Returns the available ops duration capacity for the execution.
     * Takes into account the amount leaked from the bucket up to the current consensus time.
     */
    long availableOpsDurationCapacity();

    /**
     * Consumes a given amount of ops duration units from the throttle's capacity.
     * Takes into account the amount leaked from the bucket up to the current consensus time.
     * If the amount to consume is greater than the available amount then consumes
     * the available amount and returns (does not fail).
     */
    void consumeOpsDurationThrottleCapacity(long opsDurationUnitsToConsume);
    /**
     * Returns the current utilization percentage of the high-volume throttle for the given functionality.
     * The utilization is expressed in hundredths of one percent (0 to 10,000), where 10,000 = 100%.
     */
    int highVolumeThrottleUtilization(HederaFunctionality function);

    /**
     * Starts capturing the consensus throttle rejections recorded in this operation context, including work done by
     * any of its child dispatches, until the returned capture is closed. Only one capture can be open at a time.
     *
     * @return the open capture
     * @throws IllegalStateException if a capture is already open
     */
    @NonNull
    ConsensusThrottleRejectionCapture captureConsensusThrottleRejections();

    /**
     * Records that a consensus throttle rejected work done in this operation context, including work done by any
     * of its child dispatches, into the open {@link ConsensusThrottleRejectionCapture}, if any.
     *
     * @param beyondCapacity whether the work needs more capacity than the throttle can ever hold, so the rejection
     * repeats at any time
     */
    void recordConsensusThrottleRejection(boolean beyondCapacity);
}
