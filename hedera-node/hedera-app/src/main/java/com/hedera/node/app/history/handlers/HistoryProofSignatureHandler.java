// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistorySignature;
import com.hedera.node.app.history.ReadableHistoryStore.HistorySignaturePublication;
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

@Singleton
public class HistoryProofSignatureHandler implements TransactionHandler {
    private final ProofControllers controllers;

    @Inject
    public HistoryProofSignatureHandler(@NonNull final ProofControllers controllers) {
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
        final var op = context.body().historyProofSignatureOrThrow();
        final long constructionId = op.constructionId();
        controllers.getInProgressById(constructionId).ifPresent(controller -> {
            final long nodeId = context.creatorInfo().nodeId();
            final var publication = new HistorySignaturePublication(
                    nodeId, op.signatureOrElse(HistorySignature.DEFAULT), context.consensusNow());
            if (controller.addSignaturePublication(publication)) {
                final var historyStore = context.storeFactory().writableStore(WritableHistoryStore.class);
                historyStore.addSignature(constructionId, publication);
            }
        });
    }
}
