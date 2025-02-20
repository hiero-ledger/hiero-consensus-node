// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.WorkflowException;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class HintsPartialSignatureHandler implements TransactionHandler {
    private final HintsContext context;
    private final HintsLibrary library;

    @Inject
    public HintsPartialSignatureHandler(@NonNull final HintsContext context, @NonNull final HintsLibrary library) {
        this.context = requireNonNull(context);
        this.library = requireNonNull(library);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) {
        requireNonNull(context);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) {
        requireNonNull(context);
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws WorkflowException {
        requireNonNull(context);
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
