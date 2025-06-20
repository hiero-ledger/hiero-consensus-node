// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.lifecycle;

/**
 * A state in a Transaction's lifecycle.
 * TransactionState and TransactionType together denotes an entity's status in the Entity's lifecycle
 */
public enum TransactionState {
    /**
     * when the transaction is generated
     */
    INITIALIZED,
    /**
     * when the transaction has been submitted successfully
     */
    SUBMITTED,
    /**
     * when the transaction fails to be submitted
     */
    SUBMISSION_FAILED,
    /**
     * when the transaction is handled successfully,
     * or when the entity is expired. In this case, corresponding TransactionType should be Expire.
     */
    HANDLED,
    /**
     * when handling this transaction, some exception happens so it is not handled successfully
     */
    HANDLE_FAILED,
    /**
     * when a transaction does not match type of the entity it is about to operate
     */
    HANDLE_ENTITY_TYPE_MISMATCH,
    /**
     * When handling a transaction after the entity is deleted or expired;
     * or handling a transaction which performs operation, except creating, on a non-existing entity
     */
    HANDLE_REJECTED,
    /**
     * when an entity is added during reconnect
     */
    RECONNECT_ORIGIN,
    /**
     * when an entity is added during restart
     */
    RESTART_ORIGIN,
    /**
     * When a transaction's signature is verified as INVALID, as we expect
     */
    EXPECTED_INVALID_SIG,
    /**
     * When a transaction's signature is verified as INVALID, as we don't expect
     */
    INVALID_SIG,
}
