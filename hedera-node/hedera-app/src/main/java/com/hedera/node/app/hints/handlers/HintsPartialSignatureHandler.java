// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.app.hints.impl.HintsControllers;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.ConcurrentMap;

@Singleton
public class HintsPartialSignatureHandler implements TransactionHandler {
    private static final Logger logger = LogManager.getLogger(HintsPartialSignatureHandler.class);
    @NonNull
    private final ConcurrentMap<Bytes, HintsContext.Signing> signings;
    private final HintsContext hintsContext;
    private final HintsControllers controllers;

    @Inject
    public HintsPartialSignatureHandler(@NonNull ConcurrentMap<Bytes, HintsContext.Signing> signings,
                                        final HintsContext context,
                                        final HintsControllers controllers) {
        this.signings = requireNonNull(signings);
        this.hintsContext = requireNonNull(context);
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
        final var op = context.body().hintsPartialSignatureOrThrow();
        final var creator = context.creatorInfo().nodeId();
        final var hintsStore = context.storeFactory().writableStore(WritableHintsStore.class);
        final var crs = hintsStore.getCrsState().crs();
        signings.get(op.message()).incorporate(crs, op.constructionId(), creator, op.partialSignature());
    }
}
