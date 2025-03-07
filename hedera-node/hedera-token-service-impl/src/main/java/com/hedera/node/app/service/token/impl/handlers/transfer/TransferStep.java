// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.spi.workflows.WorkflowException;
import java.util.Set;

/**
 * Defines the interface for each step in the crypto transfer process.
 */
public interface TransferStep {
    /**
     * Returns the set of keys that are authorized to perform this step.
     * @param transferContext the context of the transfer
     * @return the set of keys that are authorized to perform this step
     */
    // FUTURE: all the logic in prehandle can be moved into appropriate steps
    default Set<Key> authorizingKeysIn(TransferContext transferContext) {
        return Set.of();
    }

    /**
     * Perform the step and commit changes to the modifications in state.
     * @param transferContext the context of the transfer
     * @throws WorkflowException if the step fails
     */
    void doIn(TransferContext transferContext);
}
