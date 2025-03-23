// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.pool;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Coordinates and manages a pool of transactions waiting to be submitted.
 */
public interface TransactionPool {

    /**
     * Submit a system transaction to the transaction pool. Transaction will be included in a future event, if
     * possible.
     *
     * @param transaction the system transaction to submit
     */
    @InputWireLabel("submit transaction")
    void submitSystemTransaction(@NonNull Bytes transaction);

    /**
     * Update the platform status.
     *
     * @param platformStatus the new platform status
     */
    @InputWireLabel("PlatformStatus")
    void updatePlatformStatus(@NonNull PlatformStatus platformStatus);

    /**
     * Report the amount of time that the system has been in an unhealthy state. Will receive a report of
     * {@link Duration#ZERO} when the system enters a healthy state.
     *
     * @param duration the amount of time that the system has been in an unhealthy state
     */
    @InputWireLabel("health info")
    void reportUnhealthyDuration(@NonNull final Duration duration);

    /**
     * Clear the transaction pool.
     */
    void clear();
}
