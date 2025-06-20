// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.contexts;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.node.app.blocks.impl.TranslationContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A {@link TranslationContext} implementation with metadata for a token mint.
 * @param memo The memo for the transaction
 * @param txnId The transaction ID
 * @param transaction The transaction
 * @param functionality The functionality of the transaction
 * @param serialNumbers The minted serial numbers, if the token is non-fungible
 * @param newTotalSupply The new total supply of the token
 */
public record MintOpContext(
        @NonNull String memo,
        @NonNull ExchangeRateSet transactionExchangeRates,
        @NonNull TransactionID txnId,
        @NonNull Transaction transaction,
        @NonNull HederaFunctionality functionality,
        @NonNull List<Long> serialNumbers,
        long newTotalSupply)
        implements TranslationContext {}
