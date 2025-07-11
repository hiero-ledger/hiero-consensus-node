// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.contexts;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.node.app.blocks.impl.TranslationContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A {@link TranslationContext} implementation with the id of an involved account.
 *
 * @param memo The memo for the transaction
 * @param txnId The transaction ID
 * @param signedTx The transaction
 * @param functionality The functionality of the transaction
 * @param accountId If set, the id of the involved account
 * @param evmAddress If non-empty, the EVM address of the involved account
 * @param serializedSignedTx If already known, the serialized signed transaction; otherwise null
 */
public record CryptoOpContext(
        @NonNull String memo,
        @NonNull ExchangeRateSet transactionExchangeRates,
        @NonNull TransactionID txnId,
        @NonNull SignedTransaction signedTx,
        @NonNull HederaFunctionality functionality,
        @Nullable AccountID accountId,
        @NonNull Bytes evmAddress,
        @Nullable Bytes serializedSignedTx)
        implements TranslationContext {}
