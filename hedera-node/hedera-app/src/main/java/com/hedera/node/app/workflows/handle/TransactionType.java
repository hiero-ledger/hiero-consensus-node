// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

/**
 * Enumerates the types of transactions that may be handled by the workflow. Almost all transactions are unexceptional,
 * but the first transactions at genesis and after an upgrade require special handling since the network needs to
 * prepare for all following transactions at these boundary conditions.
 */
public enum TransactionType {
    /**
     * The first transaction at network genesis.
     */
    GENESIS_TRANSACTION,
    /**
     * The first transaction after an upgrade.
     */
    POST_UPGRADE_TRANSACTION,
    /**
     * All other transactions.
     */
    ORDINARY_TRANSACTION,
}
