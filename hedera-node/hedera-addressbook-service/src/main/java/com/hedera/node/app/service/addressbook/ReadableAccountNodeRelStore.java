// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook;

import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms.
 */
public interface ReadableAccountNodeRelStore {

    /**
     * Returns the node identifier linked to the provided account.
     * Returns null if relations are not found.
     *
     * @param accountId being looked up
     * @return node identifier
     */
    @Nullable
    Long get(AccountID accountId);
}
