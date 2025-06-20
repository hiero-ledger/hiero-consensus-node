// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.ProofControllers;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class HistoryProofVoteHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(HistoryProofVoteHandler.class);
    private final ProofControllers controllers;

    @Inject
    public HistoryProofVoteHandler(@NonNull final ProofControllers controllers) {
        this.controllers = requireNonNull(controllers);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        requireNonNull(context);
        final var op = context.body().historyProofVoteOrThrow();
        final long constructionId = op.constructionId();
        final var vote = op.voteOrElse(HistoryProofVote.DEFAULT);
        controllers.getInProgressById(constructionId).ifPresent(controller -> {
            final long nodeId = context.creatorInfo().nodeId();
            final var historyStore = context.storeFactory().writableStore(WritableHistoryStore.class);
            log.info(
                    "Received {} from node{} for construction #{}",
                    vote.hasCongruentNodeId()
                            ? "vote congruent to node" + vote.congruentNodeIdOrThrow()
                            : "explicit vote",
                    context.creatorInfo().nodeId(),
                    constructionId);
            controller.addProofVote(nodeId, op.voteOrElse(HistoryProofVote.DEFAULT), historyStore);
        });
    }
}
