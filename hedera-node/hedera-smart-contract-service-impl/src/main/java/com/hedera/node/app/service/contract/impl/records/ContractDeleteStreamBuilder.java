// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.records;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code ContractDelete}.
 */
public interface ContractDeleteStreamBuilder extends DeleteCapableTransactionStreamBuilder {
    /**
     * Tracks the contract id deleted by a successful top-level contract deletion.
     *
     * @param contractId the {@link ContractID} of the deleted top-level contract
     * @return this builder
     */
    @NonNull
    ContractDeleteStreamBuilder contractID(@Nullable ContractID contractId);

    @NonNull
    ContractDeleteStreamBuilder signedTx(@NonNull final SignedTransaction txn);
}
