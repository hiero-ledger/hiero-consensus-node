// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.records;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Exposes the record customizations needed for a HAPI contract create transaction.
 */
public interface ContractCreateStreamBuilder extends StreamBuilder, ContractOperationStreamBuilder {

    /**
     * Tracks the final status of a top-level contract creation.
     *
     * @param status the final status of the contract creation
     * @return this builder
     */
    @NonNull
    ContractCreateStreamBuilder status(@NonNull ResponseCodeEnum status);

    /**
     * Tracks the contract id created by a successful top-level contract creation.
     *
     * @param contractId the {@link ContractID} of the new top-level contract
     * @return this builder
     */
    @NonNull
    ContractCreateStreamBuilder createdContractID(@Nullable ContractID contractId);

    /**
     * Tracks the account id created by a successful top-level contract creation.
     * @param accountID the {@link AccountID} of the new top-level contract
     * @return this builder
     */
    @NonNull
    ContractCreateStreamBuilder accountID(@Nullable AccountID accountID);

    /**
     * Tracks the result of a top-level contract creation.
     *
     * @param result the {@link ContractFunctionResult} of the contract creation
     * @return this builder
     */
    @NonNull
    ContractCreateStreamBuilder contractCreateResult(@Nullable ContractFunctionResult result);

    @NonNull
    ContractCreateStreamBuilder transaction(@NonNull Transaction transaction);
}
