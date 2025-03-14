// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.HistorySignature;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.history.HistoryProofKeyPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.history.HistoryProofSignatureTransactionBody;
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
                b -> b.historyProofKeyPublication(new HistoryProofKeyPublicationTransactionBody(proofKey)), onFailure);
    }

    /**
     * Submits a history proof vote to the network.
     * @param constructionId the construction id to vote on
     * @param proof history proof to vote for
     * @return a future that completes with the submission
     */
    public CompletableFuture<Void> submitProofVote(final long constructionId, @NonNull final HistoryProof proof) {
        requireNonNull(proof);
        logger.info("Submitting proof vote for construction {}", constructionId);
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        return submitIfActive(
                b -> b.historyProofVote(new HistoryProofVoteTransactionBody(constructionId, vote)), onFailure);
    }

    /**
     * Submits an assembly signature to the network.
     * @return a future that completes with the submission
     */
    public CompletableFuture<Void> submitAssemblySignature(
            final long constructionId, @NonNull final HistorySignature signature) {
        requireNonNull(signature);
        return submitIfActive(
                b -> b.historyProofSignature(new HistoryProofSignatureTransactionBody(constructionId, signature)),
                onFailure);
    }
}
