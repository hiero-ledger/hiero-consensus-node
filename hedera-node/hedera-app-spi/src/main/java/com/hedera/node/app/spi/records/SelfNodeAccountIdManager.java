// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.records;

import com.hedera.hapi.node.base.AccountID;

/**
 * Manages the persistent storage and retrieval of the self node's account ID.
 */
public interface SelfNodeAccountIdManager {

    /**
     * Retrieves the self node's account ID.
     *
     * @return the self node's account ID
     */
    AccountID getSelfNodeAccountId();

    /**
     * Creates or updates the file {@code node_account_id.txt} containing the self node's account ID.
     *
     * @param accountId the new account ID to persist
     */
    void setSelfNodeAccountId(final AccountID accountId);
}
