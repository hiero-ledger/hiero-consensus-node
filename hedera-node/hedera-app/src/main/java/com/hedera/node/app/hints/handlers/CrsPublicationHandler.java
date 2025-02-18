/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.hints.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.impl.HintsControllers;
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
public class CrsPublicationHandler implements TransactionHandler {
    private final HintsControllers controllers;

    @Inject
    public CrsPublicationHandler(final HintsControllers controllers) {
        this.controllers = requireNonNull(controllers);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().crsPublicationOrThrow();
        final var hintsStore = context.storeFactory().writableStore(WritableHintsStore.class);

        final var creatorId = context.creatorInfo().nodeId();
        controllers.getAnyInProgress().ifPresent(controller -> {
            if (hintsStore.getCrsState().hasNextContributingNodeId()
                    && creatorId == hintsStore.getCrsState().nextContributingNodeIdOrThrow()) {
                hintsStore.addCrsPublication(creatorId, op);
                controller.addCrsPublication(op, context.consensusNow(), hintsStore);
            }
        });
    }
}
