// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.google.protobuf.ByteString;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.base.utility.CommonUtils;
import org.hiero.otter.fixtures.turtle.app.EmptyTransaction;
import org.hiero.otter.fixtures.turtle.app.TurtleFreezeTransaction;
import org.hiero.otter.fixtures.turtle.app.TurtleTransaction;

/**
 * Utility class for transaction-related operations.
 */
public class TransactionFactory {

    private TransactionFactory() {}

    /**
     * Creates a new empty transaction.
     *
     * @return an empty transaction
     */
    public static TurtleTransaction createEmptyTransaction(final int nonce) {
        final EmptyTransaction emptyTransaction = EmptyTransaction.newBuilder().build();
        return TurtleTransaction.newBuilder()
                .setEmptyTransaction(emptyTransaction)
                .build();
    }

    /**
     * Creates a freeze transaction with the specified freeze time.
     *
     * @param freezeTime the freeze time for the transaction
     * @return a FreezeTransaction with the provided freeze time
     */
    public static TurtleTransaction createFreezeTransaction(@NonNull final Instant freezeTime) {
        final Timestamp timestamp = CommonPbjConverters.fromPbj(CommonUtils.toPbjTimestamp(freezeTime));
        final TurtleFreezeTransaction freezeTransaction =
                TurtleFreezeTransaction.newBuilder().setFreezeTime(timestamp).build();
        return TurtleTransaction.newBuilder()
                .setFreezeTransaction(freezeTransaction)
                .build();
    }

    /**
     * Creates a transaction with the specified inner StateSignatureTransaction.
     *
     * @param innerTxn the StateSignatureTransaction
     * @return a TurtleTransaction with the specified inner transaction
     */
    public static TurtleTransaction createStateSignatureTransaction(@NonNull final StateSignatureTransaction innerTxn) {
        final com.hedera.hapi.platform.event.legacy.StateSignatureTransaction legacyInnerTxn =
                com.hedera.hapi.platform.event.legacy.StateSignatureTransaction.newBuilder()
                        .setRound(innerTxn.round())
                        .setSignature(ByteString.copyFrom(innerTxn.signature().toByteArray()))
                        .setHash(ByteString.copyFrom(innerTxn.hash().toByteArray()))
                        .build();
        return TurtleTransaction.newBuilder()
                .setStateSignatureTransaction(legacyInnerTxn)
                .build();
    }
}
