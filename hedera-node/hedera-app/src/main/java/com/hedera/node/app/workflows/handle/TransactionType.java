// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

/**
 * Enumerates the types of transactions that may be handled by the workflow. Almost all transactions are unexceptional,
 * but the first transactions at genesis and after an upgrade require special handling since the network needs to
 * prepare for all following transactions at these boundary conditions.
 * <p>
 * Eventually won't exist because we will lift all system activity out of {@code handlePlatformTransaction()} and put
 * it in {@code handleConsensusRound()} where it does not have counter-intuitive dependencies on the {@link Dispatch}
 * being used for a user transaction.
 */
public enum TransactionType {
    /**
     * The first transaction after an upgrade.
     */
    POST_UPGRADE_TRANSACTION,
    /**
     * All other transactions.
     */
    ORDINARY_TRANSACTION,
    /**
     * A synthetic transaction
     */
    INTERNAL_TRANSACTION,
}
