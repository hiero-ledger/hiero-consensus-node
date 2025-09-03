// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule;

import com.hedera.hapi.node.base.AccountID;
import java.time.Instant;

/**
 * Additional API for the Schedule Service beyond its dispatchable handlers.
 */
public interface ScheduleServiceApi {
    /**
     * Checks if the schedule service has enough capacity to schedule a contract call
     * at the given consensus second with the specified gas limit.
     *
     * @param expiry the consensus second at which the call is to be processed
     * @param consensusNow the current consensus time
     * @param gasLimit the gas limit for the contract call
     * @param payerId the ID of the account that will pay for the contract call
     * @return true if the schedule service has enough capacity to process the contract call,
     */
    boolean hasContractCallCapacity(long expiry, Instant consensusNow, long gasLimit, AccountID payerId);
}
