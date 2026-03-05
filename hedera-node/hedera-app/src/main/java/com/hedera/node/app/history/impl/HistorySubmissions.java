// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.WrapsPhase;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.history.HistoryProofKeyPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.history.HistoryProofVoteTransactionBody;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.tss.TssSubmissions;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class HistorySubmissions extends TssSubmissions {
    private static final Logger logger = LogManager.getLogger(HistorySubmissions.class);

    private final BiConsumer<TransactionBody, String> onFailure =
            (body, reason) -> logger.warn("Failed to submit {} ({})", body, reason);

    @Inject
    public HistorySubmissions(@NonNull final Executor executor, @NonNull final AppContext appContext) {
        super(executor, appContext);
    }

    /**
     * Submits a proof key publication to the network.
     * @param proofKey the proof key to publish
     * @return a future that completes with the submission
     */
    public CompletableFuture<Void> submitProofKeyPublication(@NonNull final Bytes proofKey) {
        requireNonNull(proofKey);
        return submitIfActive(
                b -> b.historyProofKeyPublication(HistoryProofKeyPublicationTransactionBody.newBuilder()
                        .proofKey(proofKey)
                        .phase(WrapsPhase.R1)
                        .build()),
                onFailure);
    }

    /**
     * Submits a WRAPS message to the network.
     *
     * @param phase the phase of the signing protocol
     * @param message the message to submit
     * @param constructionId the construction id
     * @return a future that completes with the submission
     */
    public CompletableFuture<Void> submitWrapsSigningMessage(
            @NonNull final WrapsPhase phase, @NonNull final Bytes message, final long constructionId) {
        requireNonNull(phase);
        requireNonNull(message);
        logger.info("Submitting WRAPS {} message for construction #{}", phase, constructionId);
        return submitIfActive(
                b -> b.historyProofKeyPublication(HistoryProofKeyPublicationTransactionBody.newBuilder()
                        .phase(phase)
                        .wrapsMessage(message)
                        .constructionId(constructionId)
                        .build()),
                onFailure);
    }

    /**
     * Submits a history proof vote to the network.
     * @param constructionId the construction id to vote on
     * @param proof history proof to vote for
     * @return a future that completes with the submission
     */
    public CompletableFuture<Void> submitExplicitProofVote(
            final long constructionId, @NonNull final HistoryProof proof) {
        requireNonNull(proof);
        logger.info("Submitting proof vote for construction #{}", constructionId);
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        return submitIfActive(
                b -> b.historyProofVote(new HistoryProofVoteTransactionBody(constructionId, vote)), onFailure);
    }

    /**
     * Submits a history proof vote to the network.
     * @param constructionId the construction id to vote on
     * @param congruentNodeId the node id that has already voted for the same proof
     * @return a future that completes with the submission
     */
    public CompletableFuture<Void> submitCongruentProofVote(final long constructionId, final long congruentNodeId) {
        logger.info("Submitting proof vote congruent to node{} for construction #{}", congruentNodeId, constructionId);
        final var vote =
                HistoryProofVote.newBuilder().congruentNodeId(congruentNodeId).build();
        return submitIfActive(
                b -> b.historyProofVote(new HistoryProofVoteTransactionBody(constructionId, vote)), onFailure);
    }
}
