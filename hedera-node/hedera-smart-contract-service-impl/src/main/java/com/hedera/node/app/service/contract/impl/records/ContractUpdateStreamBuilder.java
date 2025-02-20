// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.records;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code ContractUpdate}.
 */
public interface ContractUpdateStreamBuilder extends DeleteCapableTransactionStreamBuilder {
    /**
     * Tracks the contract id updated by a successful top-level contract update operation.
     *
     * @param contractId the {@link ContractID} of the updated top-level contract
     * @return this builder
     */
    @NonNull
    ContractUpdateStreamBuilder contractID(@Nullable ContractID contractId);
}
