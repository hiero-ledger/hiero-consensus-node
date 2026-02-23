// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.contexts;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.contract.InternalCallContext;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.node.app.blocks.impl.TranslationContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * A {@link TranslationContext} implementation with the id of an involved contract.
 *
 * @param memo The memo for the transaction
 * @param txnId The transaction ID
 * @param signedTx The transaction
 * @param functionality The functionality of the transaction
 * @param contractId The id of a newly created contract
 * @param evmAddress The EVM of a newly created contract
 * @param changedNonceInfos The list of contract IDs whose nonces were changed during the transaction, if any
 * @param createdContractIds if applicable, the list of contract IDs created during the transaction
 * @param senderNonce if applicable, the nonce of the sender after the transaction
 * @param ethCallContext if applicable, the context of an internal call in the transaction
 * @param ethHash if applicable, the hash of the Ethereum transaction
 * @param serializedSignedTx If already known, the serialized signed transaction; otherwise null
 */
public record ContractOpContext(
        @NonNull String memo,
        @NonNull ExchangeRateSet transactionExchangeRates,
        @NonNull TransactionID txnId,
        @NonNull SignedTransaction signedTx,
        @NonNull HederaFunctionality functionality,
        @Nullable ContractID contractId,
        @Nullable Bytes evmAddress,
        @Nullable List<ContractNonceInfo> changedNonceInfos,
        @Nullable List<ContractID> createdContractIds,
        @Nullable Long senderNonce,
        @Nullable InternalCallContext ethCallContext,
        @NonNull Bytes ethHash,
        @Nullable Bytes serializedSignedTx)
        implements TranslationContext {}
