// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.contexts;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.PendingAirdropRecord;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.node.app.blocks.impl.TranslationContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * A {@link TranslationContext} implementation with the list of new pending airdrops.
 *
 * @param memo The memo for the transaction
 * @param txnId The transaction ID
 * @param signedTx The transaction
 * @param functionality The functionality of the transaction
 * @param pendingAirdropRecords The list of new pending airdrops
 * @param serializedSignedTx If already known, the serialized signed transaction; otherwise null
 */
public record AirdropOpContext(
        @NonNull String memo,
        @NonNull ExchangeRateSet transactionExchangeRates,
        @NonNull TransactionID txnId,
        @NonNull SignedTransaction signedTx,
        @NonNull HederaFunctionality functionality,
        @NonNull List<PendingAirdropRecord> pendingAirdropRecords,
        @Nullable Bytes serializedSignedTx)
        implements TranslationContext {}
