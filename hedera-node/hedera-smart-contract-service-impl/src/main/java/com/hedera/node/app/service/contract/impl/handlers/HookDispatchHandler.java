package com.hedera.node.app.service.contract.impl.handlers;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import edu.umd.cs.findbugs.annotations.NonNull;

import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

public class HookDispatchHandler implements TransactionHandler {
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // no-op
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().hookDispatchOrThrow();
        validateTruePreCheck(op.hasCreation() || op.hasExecution() || op.hasHookIdToDelete(), ResponseCodeEnum.INVALID_TRANSACTION_BODY);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        final var writableEvmStore = context.storeFactory().writableStore(WritableEvmHookStore.class);
        final var op = context.body().hookDispatchOrThrow();
        if (op.hasCreation()) {
            writableEvmStore.createEvmHook(op.creationOrThrow());
        } else if (op.hasExecution()) {
            throw new UnsupportedOperationException("EVM hook execution not implemented yet");
        } else if (op.hasHookIdToDelete()) {
            final var hookId = op.hookIdToDeleteOrThrow();
            writableEvmStore.markDeleted(hookId);
        }
    }
}
