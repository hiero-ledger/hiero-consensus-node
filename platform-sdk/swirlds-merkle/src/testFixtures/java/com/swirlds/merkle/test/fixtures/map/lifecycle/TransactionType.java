// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.lifecycle;

/**
 * Type of a transaction
 */
public enum TransactionType {
    /**
     * When a transaction is CreateAccount or CreateAccountFCQ
     */
    Create,
    /**
     * When a transaction is CreateAccount or CreateAccountFCQ, but the account
     * already exists in state.
     */
    CreateExistingAccount,
    /**
     * When a transaction is UpdateAccount or UpdateAccountFCQ
     */
    Update,
    /**
     * When a transaction is UpdateAccount or UpdateAccountFCQ, and
     * the account does not belong to state.
     */
    UpdateNotExistentAccount,
    /**
     * When a transaction is Append
     */
    Append,
    /**
     * When a transaction is DeleteAccount or DeleteAccountFCQ
     */
    Delete,
    /**
     * When a transaction is DeleteAccount or DeleteAccountFCQ, and
     * the account does not belong to state.
     */
    DeleteNotExistentAccount,
    /**
     * When a transaction is TransferBalance, or TransferBalanceFCQ
     */
    Transfer,
    /**
     * When an entity is removed because it is expired
     */
    Expire,
    /**
     * When an entity is added at restart or reconnect, not by a transaction
     */
    Rebuild,
    /**
     * When a transaction is creating and associating a token to an account
     */
    MintToken,
    /**
     * When a transaction is transferring an NFT
     */
    TransferToken,
    /**
     * When a transaction is burning an NFT
     */
    BurnToken
}
