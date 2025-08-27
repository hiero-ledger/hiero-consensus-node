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
     * @param serializedSignedTx the serialized transaction to convert
     * @return the constructed parts
     * @throws IllegalArgumentException if the transaction is invalid
     */
    public static TransactionParts from(@NonNull final Bytes serializedSignedTx) {
        try {
            final var signedTx = SignedTransaction.PROTOBUF.parse(serializedSignedTx);
            final Transaction wrapper;
            if (signedTx.useSerializedTxMessageHashAlgorithm()) {
                wrapper = Transaction.newBuilder()
                        .bodyBytes(signedTx.bodyBytes())
                        .sigMap(signedTx.sigMap())
                        .build();
            } else {
                wrapper = Transaction.newBuilder()
                        .signedTransactionBytes(serializedSignedTx)
                        .build();
            }
            final var body = TransactionBody.PROTOBUF.parse(signedTx.bodyBytes());
            return new TransactionParts(wrapper, body, functionOf(body));
        } catch (ParseException | UnknownHederaFunctionality e) {
            // Fail immediately with invalid transactions that should not be in any production record stream
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Constructs a {@link TransactionParts} from a serialized {@link Transaction}.
     * @param signedTx the signed transaction to convert
     * @return the constructed parts
     * @throws IllegalArgumentException if the transaction is invalid
     */
    public static TransactionParts from(@NonNull final SignedTransaction signedTx) {
        try {
            final Transaction wrapper;
            if (signedTx.useSerializedTxMessageHashAlgorithm()) {
                wrapper = Transaction.newBuilder()
                        .bodyBytes(signedTx.bodyBytes())
                        .sigMap(signedTx.sigMap())
                        .build();
            } else {
                wrapper = Transaction.newBuilder()
                        .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(signedTx))
                        .build();
            }
            final var body = TransactionBody.PROTOBUF.parse(signedTx.bodyBytes());
            return new TransactionParts(wrapper, body, functionOf(body));
        } catch (ParseException | UnknownHederaFunctionality e) {
            // Fail immediately with invalid transactions that should not be in any production record stream
            throw new IllegalArgumentException(e);
        }
    }
}
