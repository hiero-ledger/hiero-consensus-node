// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.throttle;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * A throttle that is used by HSS to limit the rate of transactions scheduled for future execution.
 */
public interface ScheduleThrottle {
    /**
     * A factory for creating {@link ScheduleThrottle} instances.
     */
    interface Factory {
        /**
         * Creates a new schedule throttle based on the capacity split and usage snapshots.
         * @param capacitySplit the split of the capacity
         * @param initialUsageSnapshots if not null, the usage snapshots the throttle should start with
         * @return the new throttle
         */
        ScheduleThrottle newScheduleThrottle(int capacitySplit, @Nullable ThrottleUsageSnapshots initialUsageSnapshots);
    }

    /**
     * Tries to consume throttle capacity for the given payer, transaction, function, time, and state.
     * @param payerId the account ID of the payer
     * @param body the transaction body
     * @param function the functionality of the transaction
     * @param now the current time
     * @return whether the capacity could be consumed
     */
    boolean allow(
            @NonNull AccountID payerId,
            @NonNull TransactionBody body,
            @NonNull HederaFunctionality function,
            @NonNull Instant now);

    /**
     * Returns the usage snapshots of the throttle.
     */
    ThrottleUsageSnapshots usageSnapshots();
}
