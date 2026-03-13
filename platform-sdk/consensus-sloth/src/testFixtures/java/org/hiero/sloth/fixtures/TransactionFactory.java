// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures;

import com.google.protobuf.ByteString;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.sloth.fixtures.network.transactions.EmptyTransaction;
import org.hiero.sloth.fixtures.network.transactions.SlothTransaction;

/**
 * Utility class for transaction-related operations.
 */
public class TransactionFactory {

    private TransactionFactory() {}

    /**
     * Creates a transaction with the specified inner StateSignatureTransaction.
     *
     * @param nonce the nonce for the transaction
     * @param innerTxn the StateSignatureTransaction
     * @return an OtterTransaction with the specified inner transaction
     */
    public static SlothTransaction createStateSignatureTransaction(
            final long nonce, @NonNull final StateSignatureTransaction innerTxn) {
        final com.hedera.hapi.platform.event.legacy.StateSignatureTransaction legacyInnerTxn =
                com.hedera.hapi.platform.event.legacy.StateSignatureTransaction.newBuilder()
                        .setRound(innerTxn.round())
                        .setSignature(ByteString.copyFrom(innerTxn.signature().toByteArray()))
                        .setHash(ByteString.copyFrom(innerTxn.hash().toByteArray()))
                        .build();
        return SlothTransaction.newBuilder()
                .setNonce(nonce)
                .setStateSignatureTransaction(legacyInnerTxn)
                .build();
    }

    /**
     * Creates a new empty transaction.
     *
     * @param nonce the nonce for the empty transaction
     * @return an empty transaction
     */
    public static SlothTransaction createEmptyTransaction(final long nonce) {
        final EmptyTransaction emptyTransaction = EmptyTransaction.newBuilder().build();
        return SlothTransaction.newBuilder()
                .setNonce(nonce)
                .setEmptyTransaction(emptyTransaction)
                .build();
    }
}
