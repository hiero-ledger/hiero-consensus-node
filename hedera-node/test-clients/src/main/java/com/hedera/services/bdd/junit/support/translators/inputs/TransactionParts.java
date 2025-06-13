// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.inputs;

import static com.hedera.hapi.util.HapiUtils.functionOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Encapsulates the parts of a transaction we care about for translating a block stream into records.
 */
public record TransactionParts(
        @NonNull Transaction wrapper, @NonNull TransactionBody body, @NonNull HederaFunctionality function) {
    public TransactionParts {
        requireNonNull(wrapper);
        requireNonNull(body);
        requireNonNull(function);
    }

    /**
     * Returns the {@link TransactionID} of the transaction.
     * @return the transaction ID
     */
    public TransactionID transactionIdOrThrow() {
        return body.transactionIDOrThrow();
    }

    /**
     * Constructs a {@link TransactionParts} from a serialized {@link Transaction}.
     * @param serializedTransaction the serialized transaction to convert
     * @return the constructed parts
     * @throws IllegalArgumentException if the transaction is invalid
     */
    public static TransactionParts from(@NonNull final Bytes serializedTransaction) {
        try {
            final var transaction = Transaction.PROTOBUF.parse(serializedTransaction);
            final var bodyBytes = bodyBytesOf(transaction);
            final var body = TransactionBody.PROTOBUF.parse(bodyBytes);
            return new TransactionParts(transaction, body, functionOf(body));
        } catch (ParseException | UnknownHederaFunctionality e) {
            // Fail immediately with invalid transactions that should not be in any production record stream
            throw new IllegalArgumentException(e);
        }
    }

    public static TransactionParts fromInner(@NonNull final Bytes serializedTransaction) {
        try {
            final var signedTxn = SignedTransaction.PROTOBUF.parse(serializedTransaction);
            final var bodyBytes = signedTxn.bodyBytes();
            final var body = TransactionBody.PROTOBUF.parse(bodyBytes);
            return new TransactionParts(null, body, functionOf(body));
        } catch (ParseException | UnknownHederaFunctionality e) {
            // Fail immediately with invalid transactions that should not be in any production record stream
            throw new IllegalArgumentException(e);
        }
    }

    private static Bytes bodyBytesOf(final Transaction txn) throws ParseException {
        if (txn.signedTransactionBytes().length() > 0) {
            final var signedTxn = SignedTransaction.PROTOBUF.parse(txn.signedTransactionBytes());
            return signedTxn.bodyBytes();
        } else {
            return txn.bodyBytes();
        }
    }
}
