// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;

import com.hedera.node.app.tss.TssBaseService;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Factory for spec operations that support exercising TSS, especially in embedded mode.
 */
public class TssVerbs {
    private TssVerbs() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns an operation that instructs the embedded {@link TssBaseService} to ignoring TSS signature requests.
     *
     * @return the operation that will ignore TSS signature requests
     */
    public static SpecOperation startIgnoringTssSignatureRequests() {
        return doingContextual(
                spec -> spec.repeatableEmbeddedHederaOrThrow().blockHashSigner().startIgnoringRequests());
    }

    /**
     * Returns an operation that instructs the embedded {@link TssBaseService} to stop ignoring TSS signature requests.
     *
     * @return the operation that will stop ignoring TSS signature requests
     */
    public static SpecOperation stopIgnoringTssSignatureRequests() {
        return doingContextual(
                spec -> spec.repeatableEmbeddedHederaOrThrow().blockHashSigner().stopIgnoringRequests());
    }

    /**
     * Submits a node transaction customized by the given body spec if the {@link HapiSpec}'s target network is an
     * embedded network in repeatable mode.
     * @param nodeId the node id
     * @param spec the spec
     * @param bodySpec the body spec
     */
    private static void submitRepeatable(
            final long nodeId,
            @NonNull final HapiSpec spec,
            @NonNull final Consumer<TransactionBody.Builder> bodySpec) {
        final var nodeAccountId = fromPbj(
                spec.targetNetworkOrThrow().getRequiredNode(byNodeId(nodeId)).getAccountId());
        final var builder = builderWith(
                spec.consensusTime(),
                nodeAccountId,
                spec.startupProperties().getDurationFromSecs("hedera.transaction.maxValidDuration"));
        bodySpec.accept(builder);
        spec.repeatableEmbeddedHederaOrThrow()
                .submit(
                        Transaction.newBuilder()
                                .setSignedTransactionBytes(SignedTransaction.newBuilder()
                                        .setBodyBytes(builder.build().toByteString())
                                        .setSigMap(SignatureMap.getDefaultInstance())
                                        .build()
                                        .toByteString())
                                .build(),
                        nodeAccountId);
    }

    private static TransactionBody.Builder builderWith(
            @NonNull final Instant validStartTime,
            @NonNull final AccountID selfAccountId,
            @NonNull final Duration validDuration) {
        return TransactionBody.newBuilder()
                .setNodeAccountID(selfAccountId)
                .setTransactionValidDuration(validDuration)
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder()
                                .setSeconds(validStartTime.getEpochSecond())
                                .setNanos(validStartTime.getNano()))
                        .setAccountID(selfAccountId));
    }
}
