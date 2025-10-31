// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.google.protobuf.ByteString;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.hiero.base.utility.CommonUtils;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.app.EmptyTransaction;
import org.hiero.otter.fixtures.app.HashPartition;
import org.hiero.otter.fixtures.app.OtterFreezeTransaction;
import org.hiero.otter.fixtures.app.OtterIssTransaction;
import org.hiero.otter.fixtures.app.OtterTransaction;

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
     * Creates an ISS transaction that will cause a self ISS for the node provided.
     *
     * @param nonce the nonce for the transaction
     * @param nodeId the id of the node to trigger a self ISS transaction for
     * @return the created ISS transaction
     */
    @NonNull
    public static OtterTransaction createSelfIssTransaction(final long nonce, @NonNull final NodeId nodeId) {
        final HashPartition hashPartition =
                HashPartition.newBuilder().addNodeId(nodeId.id()).build();
        final OtterIssTransaction issTransaction =
                OtterIssTransaction.newBuilder().addPartition(hashPartition).build();
        return OtterTransaction.newBuilder()
                .setNonce(nonce)
                .setIssTransaction(issTransaction)
                .build();
    }

    /**
     * Creates an ISS transaction that will cause the specified nodes to calculate different hashes for the round this
     * transaction reaches consensus in. Each node specified will calculate a different hash for the same round. Any
     * nodes not specified will agree on yet a different hash.
     *
     * @param nonce the nonce for the transaction
     * @param nodes the nodes that will calculate different hashes when this ISS transaction is handled
     * @return the created ISS transaction
     */
    @NonNull
    public static OtterTransaction createIssTransaction(final long nonce, @NonNull final List<Node> nodes) {
        final List<HashPartition> hashPartitions = nodes.stream()
                .map(node ->
                        HashPartition.newBuilder().addNodeId(node.selfId().id()).build())
                .toList();
        final OtterIssTransaction issTransaction =
                OtterIssTransaction.newBuilder().addAllPartition(hashPartitions).build();
        return OtterTransaction.newBuilder()
                .setNonce(nonce)
                .setIssTransaction(issTransaction)
                .build();
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
