// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import static java.util.Objects.requireNonNull;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.ToLongFunction;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Gives a value that represents each round work load
 */
public class TransactionHandlerDataCounter implements ToLongFunction<Object> {
    // Assuming 1 round/sec it would require LOW_TPS_TARGET_ROUNDS seconds
    // for the backpressure mechanism to engage at low tps
    private static final int LOW_TPS_TARGET_ROUNDS =
            25; // target a capacity of 25 rounds require an approx of 4000 minimum weight
    private final double minimumEffort;

    public TransactionHandlerDataCounter(final long roundMaxTransactionCapacity, final double targetMaxRounds) {
        // we want the mechanism of backpressure to engage soon enough in low tps set-ups
        // since even empty rounds have an effect on the load in execution side
        this.minimumEffort = (double) roundMaxTransactionCapacity / targetMaxRounds;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long applyAsLong(final Object data) {
        if (data instanceof final ConsensusRound consensusRound) {
            return (long) Math.max(minimumEffort, consensusRound.getNumAppTransactions());
        }
        return 1L;
    }

    /**
     * Creates an instance of the dataCounter
     * @param schedulerConfiguration the configuration to get the maximum capacity from
     * @return a new TransactionHandlerDataCounter
     */
    @NonNull
    public static TransactionHandlerDataCounter create(
            final @NonNull TaskSchedulerConfiguration schedulerConfiguration) {

        final long capacity = requireNonNull(schedulerConfiguration).unhandledTaskCapacity() == null
                ? 0L
                : schedulerConfiguration.unhandledTaskCapacity();

        return new TransactionHandlerDataCounter(capacity, LOW_TPS_TARGET_ROUNDS);
    }
}
