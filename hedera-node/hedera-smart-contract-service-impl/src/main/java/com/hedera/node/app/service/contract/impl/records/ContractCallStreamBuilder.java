// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.records;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Exposes the record customizations needed for a HAPI contract call transaction.
 */
public interface ContractCallStreamBuilder extends ContractOperationStreamBuilder {
    /**
     * Returns all assessed custom fees for this call.
     *
     * @return the assessed custom fees
     */
    @NonNull
    List<AssessedCustomFee> getAssessedCustomFees();

    /**
     * Tracks the final status of a top-level contract call.
     *
     * @param status the final status of the contract call
     * @return this builder
     */
    @NonNull
    ContractCallStreamBuilder status(@NonNull ResponseCodeEnum status);

    /**
     * Returns final status of this contract call's record.
     *
     * @return the final status of this contract call
     */
    @NonNull
    ResponseCodeEnum status();

    /**
     * Tracks the contract id called.
     *
     * @param contractId the {@link ContractID} called
     * @return this builder
     */
    @NonNull
    ContractCallStreamBuilder contractID(@Nullable ContractID contractId);

    /**
     * Returns the token id created.
     *
     * @return the token id created
     */
    TokenID tokenID();

    /**
     * Tracks the result of a top-level contract call.
     *
     * @param result the {@link ContractFunctionResult} of the contract call
     * @return this builder
     */
    @NonNull
    @Deprecated
    ContractCallStreamBuilder contractCallResult(@Nullable ContractFunctionResult result);

    /**
     * Tracks (or clears) the result of the top-level EVM call transaction.
     * @param result the {@link EvmTransactionResult} of the contract call
     * @return this builder
     */
    @NonNull
    ContractCallStreamBuilder evmCallTransactionResult(@Nullable EvmTransactionResult result);

    /**
     * Tracks the transaction contained in child records resulting from the contract call.
     *
     * @param txn the transaction
     * @return this builder
     */
    @NonNull
    ContractCallStreamBuilder signedTx(@NonNull final SignedTransaction txn);

    /**
     * Gets the newly minted serial numbers.
     *
     * @return the newly minted serial numbers
     */
    List<Long> serialNumbers();

    /**
     * Gets the new total supply of a token, e.g. after minting or burning.
     *
     * @return new total supply of a token
     */
    long getNewTotalSupply();

    /**
     * @param prngBytes bytes to use for entropy
     * @return the contract call stream builder
     */
    @NonNull
    ContractCallStreamBuilder entropyBytes(@NonNull final Bytes prngBytes);

    /**
     * Returns the number of auto-associations created in the dispatch.
     *
     * @return the number of auto-associations created
     */
    int getNumAutoAssociations();

    /**
     * Returns the schedule ID.
     *
     * @return the schedule ID
     */
    ScheduleID scheduleID();
}
