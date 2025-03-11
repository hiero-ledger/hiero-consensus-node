// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking {@code NodeStakeUpdate} at midnight UTC every day.
 */
public interface NodeStakeUpdateStreamBuilder extends StreamBuilder {
    /**
     * Sets the status.
     *
     * @param status the status
     * @return the builder
     */
    NodeStakeUpdateStreamBuilder status(@NonNull ResponseCodeEnum status);

    /**
     * Sets the transaction.
     *
     * @param transaction the transaction
     * @return the builder
     */
    @NonNull
    NodeStakeUpdateStreamBuilder transaction(@NonNull Transaction transaction);

    /**
     * Sets the record's memo.
     *
     * @param memo the memo
     * @return the builder
     */
    @NonNull
    NodeStakeUpdateStreamBuilder memo(@NonNull String memo);
}
