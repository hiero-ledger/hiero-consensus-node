// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;

import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Base interface for objects that have extra context needed to easily translate a {@link TransactionResult} and,
 * optionally, a {@link TransactionOutput} into a {@link TransactionRecord} to be returned from a query.
 */
public interface TranslationContext {
    /**
     * Returns the memo of the transaction.
     * @return the memo
     */
    String memo();

    /**
     * The exchange rate set to include in the receipt for this transaction.
     * @return an exchange rate set applicable to the transaction receipt.
     */
    ExchangeRateSet transactionExchangeRates();

    /**
     * Returns the transaction ID of the transaction.
     * @return the transaction ID
     */
    TransactionID txnId();

    /**
     * Returns the transaction itself.
     * @return the transaction
     */
    Transaction transaction();

    /**
     * Returns the functionality of the transaction.
     * @return the functionality
     */
    HederaFunctionality functionality();

    /**
     * Returns the hash of the transaction.
     * @return the hash
     */
    default Bytes transactionHash() {
        final Bytes transactionBytes;
        final var txn = transaction();
        if (txn.signedTransactionBytes().length() > 0) {
            transactionBytes = txn.signedTransactionBytes();
        } else {
            transactionBytes = Transaction.PROTOBUF.toBytes(txn);
        }
        return Bytes.wrap(noThrowSha384HashOf(transactionBytes.toByteArray()));
    }
}
