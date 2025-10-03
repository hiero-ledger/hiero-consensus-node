// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.HookEntityId.EntityIdOneOfType.ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_LAMBDA_STORAGE_UPDATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_IS_NOT_A_LAMBDA;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.LAMBDA_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.LAMBDA_STORAGE_UPDATE_BYTES_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOO_MANY_LAMBDA_STORAGE_UPDATES;
import static com.hedera.hapi.node.state.hooks.EvmHookType.LAMBDA;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.minimalRepresentationOf;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.hooks.LambdaMappingEntry;
import com.hedera.hapi.node.hooks.LambdaStorageSlot;
import com.hedera.node.app.service.contract.ReadableEvmHookStore;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.HooksConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class LambdaSStoreHandler implements TransactionHandler {
    public static final long MAX_UPDATE_BYTES_LEN = 32L;

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
                validateSlot(update.storageSlotOrThrow());
            } else if (update.hasMappingEntries()) {
                final var mappingEntries = update.mappingEntriesOrThrow();
                validateWord(mappingEntries.mappingSlot());
                for (final var entry : mappingEntries.entries()) {
                    validateEntry(entry);
                }
            } else {
                throw new PreCheckException(EMPTY_LAMBDA_STORAGE_UPDATE);
            }
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().lambdaSstoreOrThrow();
        final var store = context.createStore(ReadableEvmHookStore.class);
        final var hook = store.getEvmHook(op.hookIdOrThrow());
        validateTruePreCheck(hook != null, HOOK_NOT_FOUND);
        validateTruePreCheck(hook.type() == LAMBDA, HOOK_IS_NOT_A_LAMBDA);
        final var ownerAccountId = hook.hookIdOrThrow().entityIdOrThrow().accountIdOrThrow();
        if (hook.hasAdminKey()) {
            // Storage for a lambda with an admin key can be managed by either the creator or the admin
            context.requireKeyOrThrow(
                    ownerAccountId,
                    ownerKey -> Key.newBuilder()
                            .thresholdKey(ThresholdKey.newBuilder()
                                    .threshold(1)
                                    .keys(new KeyList(List.of(ownerKey, hook.adminKeyOrThrow()))))
                            .build(),
                    INVALID_HOOK_ID);
        } else {
            context.requireKeyOrThrowOnDeleted(ownerAccountId, INVALID_HOOK_ID);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().lambdaSstoreOrThrow();
        final var lambdaStore = context.storeFactory().writableStore(WritableEvmHookStore.class);
        final var storageUpdates = op.storageUpdates();
        final var config = context.configuration().getConfigData(HooksConfig.class);
        validateTrue(storageUpdates.size() <= config.maxLambdaSStoreUpdates(), TOO_MANY_LAMBDA_STORAGE_UPDATES);
        final int delta = lambdaStore.updateStorage(op.hookIdOrThrow(), op.storageUpdates());
        if (delta != 0) {
            final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
            final var ownerAccountId = op.hookIdOrThrow().entityIdOrThrow().accountIdOrThrow();
            tokenServiceApi.updateLambdaStorageSlots(ownerAccountId, delta);
        }
    }

    private void validateSlot(@NonNull final LambdaStorageSlot slot) throws PreCheckException {
        validateWord(slot.key());
        validateWord(slot.value());
    }

    private void validateEntry(@NonNull final LambdaMappingEntry entry) throws PreCheckException {
        if (entry.hasKey()) {
            validateWord(entry.keyOrThrow());
        }
        validateWord(entry.value());
    }

    private void validateWord(@NonNull final Bytes bytes) throws PreCheckException {
        validateTruePreCheck(bytes.length() <= MAX_UPDATE_BYTES_LEN, LAMBDA_STORAGE_UPDATE_BYTES_TOO_LONG);
        final var minimalBytes = minimalRepresentationOf(bytes);
        validateTruePreCheck(bytes == minimalBytes, LAMBDA_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION);
    }
}
