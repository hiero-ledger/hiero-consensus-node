// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

/**
 * The phase of the transaction handling process.
 */
public enum TransactionHandlerPhase {
    /**
     * Nothing is happening.
     */
    IDLE,
    /**
     * The consensus fields of the events in a round are being populated.
     */
    SETTING_EVENT_CONSENSUS_DATA,
    /**
     * The round handler is waiting for transaction prehandling to complete.
     */
    WAITING_FOR_PREHANDLE,
    /**
     * The transactions in the round are being applied to the state.
     */
    HANDLING_CONSENSUS_ROUND,
    /**
     * The platform state is being updated with results from the round.
     */
    UPDATING_PLATFORM_STATE,
    /**
     * The platform state is being updated with the running hash of the round.
     */
    UPDATING_PLATFORM_STATE_RUNNING_HASH,
    /**
     * The handler is getting the state to sign.
     */
    GETTING_STATE_TO_SIGN,
    /**
     * The handler is creating a new signed state instance.
     */
    CREATING_SIGNED_STATE
}
