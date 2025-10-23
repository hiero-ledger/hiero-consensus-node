// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.contexts;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.node.app.blocks.impl.TranslationContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A {@link TranslationContext} implementation with the metadata of an HCS message submission.
 *
 * @param memo The memo for the transaction
 * @param txnId The transaction ID
 * @param signedTx The transaction
 * @param functionality The functionality of the transaction
 * @param runningHash The new running hash of the HCS topic
 * @param runningHashVersion The running hash version of the HCS topic
 * @param sequenceNumber The new sequence number of the HCS message
 * @param serializedSignedTx If already known, the serialized signed transaction; otherwise null
 */
public record SubmitOpContext(
        @NonNull String memo,
        @NonNull ExchangeRateSet transactionExchangeRates,
        @NonNull TransactionID txnId,
        @NonNull SignedTransaction signedTx,
        @NonNull HederaFunctionality functionality,
        @NonNull Bytes runningHash,
        long runningHashVersion,
        long sequenceNumber,
        @Nullable Bytes serializedSignedTx)
        implements TranslationContext {}
