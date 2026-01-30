// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.ProofControllers;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.TssConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class HistoryProofKeyPublicationHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(HistoryProofKeyPublicationHandler.class);
    private final ProofControllers controllers;

    @Inject
    public HistoryProofKeyPublicationHandler(@NonNull final ProofControllers controllers) {
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
        final var op = context.body().historyProofKeyPublicationOrThrow();
        final var historyStore = context.storeFactory().writableStore(WritableHistoryStore.class);
        final long nodeId = context.creatorInfo().nodeId();
        final var tssConfig = context.configuration().getConfigData(TssConfig.class);
        if (op.hasProofKey()) {
            final var key = op.proofKeyOrThrow();
            log.info("node{} published new proof key '{}'", nodeId, key);
            // Returns true if the key is immediately in use, hence needs to be given to the in-progress controller
            if (historyStore.setProofKey(nodeId, key, context.consensusNow())) {
                controllers.getAnyInProgress(tssConfig).ifPresent(controller -> {
                    final var publication = new ProofKeyPublication(nodeId, key, context.consensusNow());
                    controller.addProofKeyPublication(publication);
                    log.info("  - Added proof key to ongoing construction #{}", controller.constructionId());
                });
            }
        } else if (op.hasWrapsMessage()) {
            final var message = op.wrapsMessageOrThrow();
            log.info("node{} published new WRAPS message '{}'", nodeId, message);
            controllers.getAnyInProgress(tssConfig).ifPresent(controller -> {
                final var publication =
                        new WrapsMessagePublication(nodeId, message, op.phase(), context.consensusNow());
                if (controller.addWrapsMessagePublication(publication, historyStore)) {
                    historyStore.addWrapsMessage(controller.constructionId(), publication);
                }
            });
        }
    }
}
