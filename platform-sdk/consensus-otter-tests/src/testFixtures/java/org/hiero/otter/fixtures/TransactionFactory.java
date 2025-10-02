// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.google.protobuf.ByteString;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.utility.CommonUtils;
import org.hiero.otter.fixtures.app.EmptyTransaction;
import org.hiero.otter.fixtures.app.HashPartition;
import org.hiero.otter.fixtures.app.OtterFreezeTransaction;
import org.hiero.otter.fixtures.app.OtterIssTransaction;
import org.hiero.otter.fixtures.app.OtterTransaction;
import org.hiero.otter.fixtures.network.Partition;

/**
 * Utility class for transaction-related operations.
 */
public class TransactionFactory {

    private TransactionFactory() {}

    /**
     * Creates a new empty transaction.
     *
     * @param nonce the nonce for the empty transaction
     * @return an empty transaction
     */
    public static OtterTransaction createEmptyTransaction(final long nonce) {
        final EmptyTransaction emptyTransaction = EmptyTransaction.newBuilder().build();
        return OtterTransaction.newBuilder()
                .setNonce(nonce)
                .setEmptyTransaction(emptyTransaction)
                .build();
    }

    /**
     * Creates a freeze transaction with the specified freeze time.
     *
     * @param nonce the nonce for the transaction
     * @param freezeTime the freeze time for the transaction
     * @return a FreezeTransaction with the provided freeze time
     */
    @NonNull
    public static OtterTransaction createFreezeTransaction(final long nonce, @NonNull final Instant freezeTime) {
        final Timestamp timestamp = CommonPbjConverters.fromPbj(CommonUtils.toPbjTimestamp(freezeTime));
        final OtterFreezeTransaction freezeTransaction =
                OtterFreezeTransaction.newBuilder().setFreezeTime(timestamp).build();
        return OtterTransaction.newBuilder()
                .setNonce(nonce)
                .setFreezeTransaction(freezeTransaction)
                .build();
    }

    /**
     * Creates an ISS transaction with the specified partitions. Nodes in each partition will calculate the same hash
     * for the consensus round this transaction is handled in. Unspecified nodes will calculate a different hash which
     * they agree on.
     *
     * @param nonce the nonce for the transaction
     * @param partitions the list of partitions for the ISS transaction
     * @return the created ISS transaction
     */
    @NonNull
    public static OtterTransaction createIssTransaction(final long nonce, @NonNull final List<Partition> partitions) {
        final Set<HashPartition> hashPartitions = partitions.stream()
                .map(p -> HashPartition.newBuilder()
                        .addAllNodeId(p.nodes().stream()
                                .map(TransactionFactory::getId)
                                .toList())
                        .build())
                .collect(Collectors.toSet());
        final OtterIssTransaction issTransaction =
                OtterIssTransaction.newBuilder().addAllPartition(hashPartitions).build();
        return OtterTransaction.newBuilder()
                .setNonce(nonce)
                .setIssTransaction(issTransaction)
                .build();
    }

    private static long getId(@NonNull final Node node) {
        return node.selfId().id();
    }

    /**
     * Creates a transaction with the specified inner StateSignatureTransaction.
     *
     * @param nonce the nonce for the transaction
     * @param innerTxn the StateSignatureTransaction
     * @return a TurtleTransaction with the specified inner transaction
     */
    public static OtterTransaction createStateSignatureTransaction(
            final long nonce, @NonNull final StateSignatureTransaction innerTxn) {
        final com.hedera.hapi.platform.event.legacy.StateSignatureTransaction legacyInnerTxn =
                com.hedera.hapi.platform.event.legacy.StateSignatureTransaction.newBuilder()
                        .setRound(innerTxn.round())
                        .setSignature(ByteString.copyFrom(innerTxn.signature().toByteArray()))
                        .setHash(ByteString.copyFrom(innerTxn.hash().toByteArray()))
                        .build();
        return OtterTransaction.newBuilder()
                .setNonce(nonce)
                .setStateSignatureTransaction(legacyInnerTxn)
                .build();
    }
}
