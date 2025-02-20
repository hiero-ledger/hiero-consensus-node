// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import java.util.List;

/**
 * A {@code StreamBuilder} specialization for reading the transfer list from child records.
 */
public interface ChildStreamBuilder {

    /**
     * Get the transfer list from the child record.
     *
     * @return the transfer list
     */
    TransferList transferList();

    /**
     * Get the token transfer lists, if any, from the child record.
     *
     * @return the token transfer lists
     */
    List<TokenTransferList> tokenTransferLists();
}
