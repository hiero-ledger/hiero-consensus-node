// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.throttle;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.AppContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * A throttle that can be used to limit the rate of transactions. Provided in the {@link AppContext} so that services
 * can align to the application's strategy for throttling transactions.
 */
public interface Throttle {
    /**
     * A factory for creating {@link Throttle} instances.
     */
    interface Factory {
        /**
         * Creates a new throttle based on the capacity split and usage snapshots.
         * @param capacitySplit the split of the capacity
         * @param initialUsageSnapshots if not null, the usage snapshots the throttle should start with
         * @return the new throttle
         */
        Throttle newThrottle(int capacitySplit, @Nullable ThrottleUsageSnapshots initialUsageSnapshots);
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
