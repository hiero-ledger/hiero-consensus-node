// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.records;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of {@link HederaFunctionality#ETHEREUM_TRANSACTION} transaction.
 */
public interface EthereumTransactionStreamBuilder extends ContractOperationStreamBuilder {
    /**
     * Tracks the final status of a HAPI Ethereum transaction.
     *
     * @param status the final status of the Ethereum transaction
     * @return this builder
     */
    @NonNull
    EthereumTransactionStreamBuilder status(@NonNull ResponseCodeEnum status);

    /**
     * Tracks the contract id called or created by the HAPI Ethereum transaction.
     *
     * @param contractId the {@link ContractID} called or created
     * @return this builder
     */
    @NonNull
    EthereumTransactionStreamBuilder contractID(@Nullable ContractID contractId);

    /**
     * Tracks the nonce of the sender after the HAPI Ethereum transaction.
     * @return this builder
     */
    @NonNull
    EthereumTransactionStreamBuilder newSenderNonce(long senderNonce);

    /**
     * Tracks the result of a HAPI Ethereum transaction performing a top-level contract call.
     *
     * @param result the {@link ContractFunctionResult} of the contract call
     * @return this builder
     */
    @NonNull
    @Deprecated
    EthereumTransactionStreamBuilder contractCallResult(@Nullable ContractFunctionResult result);

    /**
     * Tracks the result of a HAPI Ethereum transaction performing a top-level contract creation.
     *
     * @param result the {@link ContractFunctionResult} of the contract creation
     * @return this builder
     */
    @NonNull
    @Deprecated
    EthereumTransactionStreamBuilder contractCreateResult(@Nullable ContractFunctionResult result);

    /**
     * Tracks the hash of a HAPI Ethereum transaction.
     *
     * @param ethereumHash the {@link Bytes} of the Ethereum transaction hash
     * @return this builder
     */
    @NonNull
    EthereumTransactionStreamBuilder ethereumHash(@NonNull Bytes ethereumHash);
}
