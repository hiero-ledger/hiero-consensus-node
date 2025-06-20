// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.records;

import com.hedera.hapi.node.base.TransferList;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;

/**
 * A {@code StreamBuilder} specialization that supports updating its transfer list to the result
 * of replaying fee charging events.
 */
public interface ReplayableFeeStreamBuilder extends StreamBuilder {
    /**
     * Sets the transfer list to the result of replaying the fees charged in the transaction.
     * @param transferList the transfer list to set
     * @throws IllegalStateException if the builder has not been rolled back
     */
    void setReplayedFees(TransferList transferList);
}
