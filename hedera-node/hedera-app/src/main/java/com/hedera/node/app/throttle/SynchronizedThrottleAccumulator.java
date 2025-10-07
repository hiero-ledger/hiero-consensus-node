// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.throttle.annotations.IngestThrottle;
import com.hedera.node.app.workflows.TransactionInfo;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Keeps track of the amount of usage of different TPS throttle categories and gas, and returns whether a given
 * transaction or query should be throttled based on that.
 * Meant to be used in multithreaded context
 */
@Singleton
public class SynchronizedThrottleAccumulator {

    private final InstantSource instantSource;
    private final ThrottleAccumulator frontendThrottle;

    @NonNull
    private Instant lastDecisionTime = Instant.EPOCH;

    @Inject
    public SynchronizedThrottleAccumulator(
            @NonNull final InstantSource instantSource,
            @NonNull @IngestThrottle final ThrottleAccumulator frontendThrottle) {
        this.instantSource = requireNonNull(instantSource);
        this.frontendThrottle = requireNonNull(frontendThrottle, "frontendThrottle must not be null");
    }

    /**
     * Updates the throttle requirements for the given transaction and returns whether the transaction
     * should be throttled for the current time(Instant.now).
     *
     * @param txnInfo the transaction to update the throttle requirements for
     * @param state the current state of the node
     * @param throttleUsages a list to accumulate throttle usages during the decision
     * @return whether the transaction should be throttled
     */
    public synchronized boolean shouldThrottle(
            @NonNull final TransactionInfo txnInfo,
            @NonNull final State state,
            @NonNull final List<ThrottleUsage> throttleUsages) {
        requireNonNull(txnInfo);
        requireNonNull(state);
        requireNonNull(throttleUsages);
        setDecisionTime(instantSource.instant());
        return frontendThrottle.checkAndEnforceThrottle(txnInfo, lastDecisionTime, state, throttleUsages);
    }

    /**
     * Updates the throttle requirements for the given query and returns whether the query should be throttled for the
     * current time(Instant.now).
     *
     * @param queryFunction the functionality of the query
     * @param query the query to update the throttle requirements for
     * @param state the current state of the node
     * @param queryPayerId the payer id of the query
     * @return whether the query should be throttled
     */
    public synchronized boolean shouldThrottle(
            @NonNull final HederaFunctionality queryFunction,
            @NonNull final Query query,
            @NonNull final State state,
            @Nullable AccountID queryPayerId) {
        requireNonNull(query);
        requireNonNull(queryFunction);
        setDecisionTime(instantSource.instant());
        return frontendThrottle.checkAndEnforceThrottle(queryFunction, lastDecisionTime, query, state, queryPayerId);
    }

    private void setDecisionTime(@NonNull final Instant time) {
        lastDecisionTime = time.isBefore(lastDecisionTime) ? lastDecisionTime : time;
    }
}
