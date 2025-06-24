// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.contexts;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
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
 * @param transaction The transaction
 * @param functionality The functionality of the transaction
 * @param contractId The id of a newly created contract
 * @param evmAddress The EVM address of the new contract, if applicable
 * @param stateChanges
 */
public record ContractOpContext(
        @NonNull String memo,
        @NonNull ExchangeRateSet transactionExchangeRates,
        @NonNull TransactionID txnId,
        @NonNull Transaction transaction,
        @NonNull HederaFunctionality functionality,
        @Nullable ContractID contractId,
        @NonNull Bytes evmAddress,
        @NonNull List<StateChange> stateChanges)
        implements TranslationContext {}
