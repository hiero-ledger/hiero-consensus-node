// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.HookEntityId.EntityIdOneOfType.ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_LAMBDA_STORAGE_UPDATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.LAMBDA_STORAGE_KEY_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.LAMBDA_STORAGE_VALUE_TOO_LONG;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
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
public class LambdaSStoreHandler implements TransactionHandler {
    private static final long MAX_KV_LEN = 32L;

    @Inject
    public LambdaSStoreHandler() {
        // Dagger2
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().lambdaSstoreOrThrow();
        validateTruePreCheck(op.hasHookId(), INVALID_HOOK_ID);
        final var hookId = op.hookIdOrThrow();
        validateTruePreCheck(hookId.hasEntityId(), INVALID_HOOK_ID);
        final var ownerType = hookId.entityIdOrThrow().entityId().kind();
        validateTruePreCheck(ownerType == ACCOUNT_ID, INVALID_HOOK_ID);
        for (final var update : op.storageUpdates()) {
            if (update.hasStorageSlot()) {
                final var slot = update.storageSlotOrThrow();
                validateTruePreCheck(slot.key().length() <= MAX_KV_LEN, LAMBDA_STORAGE_KEY_TOO_LONG);
                validateTruePreCheck(slot.value().length() <= MAX_KV_LEN, LAMBDA_STORAGE_VALUE_TOO_LONG);
            } else if (update.hasMappingEntries()) {
                final var mappingEntries = update.mappingEntriesOrThrow();
                validateTruePreCheck(mappingEntries.mappingSlot().length() <= MAX_KV_LEN, LAMBDA_STORAGE_KEY_TOO_LONG);
                for (final var entry : mappingEntries.entries()) {
                    validateTruePreCheck(entry.key().length() <= MAX_KV_LEN, LAMBDA_STORAGE_KEY_TOO_LONG);
                    validateTruePreCheck(entry.value().length() <= MAX_KV_LEN, LAMBDA_STORAGE_VALUE_TOO_LONG);
                }
            } else {
                throw new PreCheckException(INVALID_LAMBDA_STORAGE_UPDATE);
            }
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().lambdaSstoreOrThrow();
        context.requireKeyOrThrow(op.hookIdOrThrow().entityIdOrThrow().accountIdOrThrow(), INVALID_HOOK_ID);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().lambdaSstoreOrThrow();
        final var lambdaStore = context.storeFactory().writableStore(WritableEvmHookStore.class);
        lambdaStore.updateSlots(op.hookIdOrThrow(), op.storageUpdates());
    }
}
