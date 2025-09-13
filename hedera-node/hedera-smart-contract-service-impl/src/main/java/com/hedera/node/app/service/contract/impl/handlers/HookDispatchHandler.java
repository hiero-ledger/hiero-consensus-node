// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOKS_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.contract.impl.utils.HookValidationUtils.validateHook;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HookId;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.HooksConfig;
import edu.umd.cs.findbugs.annotations.NonNull;

public class HookDispatchHandler implements TransactionHandler {
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // no-op
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().hookDispatchOrThrow();
        validateTruePreCheck(op.hasCreation() || op.hasExecution() || op.hasHookIdToDelete(), INVALID_TRANSACTION_BODY);
        if (op.hasCreation()) {
            validateHook(op.creationOrThrow().details());
        } else if (op.hasHookIdToDelete()) {
            final var deletion = op.hookIdToDeleteOrThrow();
            validateTruePreCheck(deletion.hookId() != 0L, INVALID_HOOK_ID);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        final var evmHookStore = context.storeFactory().writableStore(WritableEvmHookStore.class);
        final var op = context.body().hookDispatchOrThrow();
        final var hookConfig = context.configuration().getConfigData(HooksConfig.class);
        validateTrue(hookConfig.hooksEnabled(), HOOKS_NOT_ENABLED);

        switch (op.action().kind()) {
            case CREATION -> {
                final var creation = op.creationOrThrow();
                final var details = creation.details();
                final var hook = evmHookStore.getEvmHook(new HookId(creation.entityId(), details.hookId()));
                validateTrue(hook == null, HOOK_ID_IN_USE);
                if (details.hasAdminKey()) {
                    context.attributeValidator().validateKey(details.adminKeyOrThrow(), INVALID_HOOK_ADMIN_KEY);
                }

                evmHookStore.createEvmHook(op.creationOrThrow());
            }
            case HOOK_ID_TO_DELETE -> {
                final var deletion = op.hookIdToDeleteOrThrow();
                final var hook = evmHookStore.getEvmHook(new HookId(deletion.entityId(), deletion.hookId()));
                validateTrue(hook != null, HOOK_NOT_FOUND);
                validateTrue(!hook.deleted(), HOOK_DELETED);

                evmHookStore.removeOrMarkDeleted(op.hookIdToDeleteOrThrow());
            }
            case EXECUTION -> throw new UnsupportedOperationException("EVM hook execution not implemented yet");
        }
    }
}
