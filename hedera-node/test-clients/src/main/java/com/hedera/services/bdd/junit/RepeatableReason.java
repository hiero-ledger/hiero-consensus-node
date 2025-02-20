// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

/**
 * Enumerates reasons a {@link RepeatableHapiTest} is marked as such.
 */
public enum RepeatableReason {
    /**
     * The test takes excessively long to run without virtual time.
     */
    NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION,
    /**
     * The test needs time to "stop" after each transaction is handled because it wants to assert
     * something about how the last-assigned consensus time is used.
     */
    NEEDS_LAST_ASSIGNED_CONSENSUS_TIME,
    /**
     * The test needs the handle workflow to be synchronous.
     */
    NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW,
    /**
     * The test needs to control behavior of the TSS subsystem.
     */
    NEEDS_TSS_CONTROL,
    /**
     * The test must directly access state to assert expectations that cannot be verified through the gRPC API.
     */
    NEEDS_STATE_ACCESS,
    /**
     * The test requires changes to the network throttle definitions, which might break
     * other tests if they expect the default throttles.
     */
    THROTTLE_OVERRIDES,
    /**
     * The test uses the state signature transaction callback.
     */
    USES_STATE_SIGNATURE_TRANSACTION_CALLBACK,
}
