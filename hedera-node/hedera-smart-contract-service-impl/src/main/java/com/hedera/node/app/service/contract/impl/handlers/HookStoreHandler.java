// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.HookEntityId.EntityIdOneOfType.UNSET;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_EVM_HOOK_STORAGE_UPDATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EVM_HOOK_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EVM_HOOK_STORAGE_UPDATE_BYTES_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_IS_NOT_AN_EVM_HOOK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOO_MANY_EVM_HOOK_STORAGE_UPDATES;
import static com.hedera.hapi.node.state.hooks.HookType.EVM_HOOK;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.asAccountId;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.leftPad32;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.minimalRepresentationOf;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.slotKeyOfMappingEntry;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.hooks.EvmHookMappingEntry;
import com.hedera.hapi.node.hooks.EvmHookStorageSlot;
import com.hedera.hapi.node.hooks.EvmHookStorageUpdate;
import com.hedera.hapi.node.state.hooks.EvmHookSlotKey;
import com.hedera.node.app.service.contract.ReadableEvmHookStore;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.HooksConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class HookStoreHandler implements TransactionHandler {
    private static final Logger log = LoggerFactory.getLogger(HookStoreHandler.class);

    /**
     * The gas costs of various {@code SSTORE} opcode scenarios in the EVM.
     */
    public static final long ZERO_INTO_ZERO_GAS_COST = 2_100L;

    public static final long NONZERO_INTO_ZERO_GAS_COST = 22_100L;
    public static final long ZERO_INTO_NONZERO_GAS_COST = 200L;
    public static final long NONZERO_INTO_NONZERO_GAS_COST = 5_000L;
    public static final long NOOP_NONZERO_INTO_NONZERO_GAS_COST = 2_100L;

    public static final long MAX_UPDATE_BYTES_LEN = 32L;

    @Inject
    public HookStoreHandler() {
        // Dagger2
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().hookStoreOrThrow();
        validateTruePreCheck(op.hasHookId(), INVALID_HOOK_ID);
        final var hookId = op.hookIdOrThrow();
        validateTruePreCheck(hookId.hasEntityId(), INVALID_HOOK_ID);
        final var ownerType = hookId.entityIdOrThrow().entityId().kind();
        validateTruePreCheck(ownerType != UNSET, INVALID_HOOK_ID);
        validateFalsePreCheck(op.storageUpdates().isEmpty(), EMPTY_EVM_HOOK_STORAGE_UPDATE);
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
                throw new PreCheckException(EMPTY_EVM_HOOK_STORAGE_UPDATE);
            }
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().hookStoreOrThrow();
        final var store = context.createStore(ReadableEvmHookStore.class);
        // We translate any contract id used at the HAPI boundary for internal simplicity
        final var hookId = effectiveHookId(op.hookIdOrThrow());
        final var hook = store.getEvmHook(hookId);
        // Since we only create hooks using numeric ids, this implicitly asserts hookEntityId uses a numeric id
        validateTruePreCheck(hook != null, HOOK_NOT_FOUND);
        validateTruePreCheck(hook.type() == EVM_HOOK, HOOK_IS_NOT_AN_EVM_HOOK);
        // (FUTURE) As non-account entities acquire hooks, switch on more cases here
        final var ownerAccountId = hookId.entityIdOrThrow().accountIdOrThrow();
        if (hook.hasAdminKey()) {
            // Storage for an EVM with an admin key can be managed by either the creator or the admin
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
        final var op = context.body().hookStoreOrThrow();
        final var evmHookStore = context.storeFactory().writableStore(WritableEvmHookStore.class);
        final var storageUpdates = op.storageUpdates();
        final var config = context.configuration().getConfigData(HooksConfig.class);
        validateTrue(storageUpdates.size() <= config.maxHookStoreUpdates(), TOO_MANY_EVM_HOOK_STORAGE_UPDATES);
        // We translate any contract id used at the HAPI boundary for internal simplicity
        final var hookId = effectiveHookId(op.hookIdOrThrow());
        final int delta = evmHookStore.updateStorage(hookId, op.storageUpdates());
        validateTrue(
                evmHookStore.numStorageSlotsInState() <= config.maxEvmHookStorageSlots(),
                MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED);
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        tokenServiceApi.updateHookStorageSlots(
                hookId.entityIdOrThrow().accountIdOrThrow(),
                delta,
                // But if the user expected a contract, enforce that here
                op.hookIdOrThrow().entityIdOrThrow().hasContractId());
    }

    @Override
    public @NonNull Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var calculator = feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT);
        calculator.resetUsage();
        final var op = feeContext.body().hookStoreOrThrow();
        long effectiveGas = 0L;
        try {
            final var hookId = effectiveHookId(op.hookIdOrThrow());
            final var store = feeContext.readableStore(ReadableEvmHookStore.class);
            for (final var update : op.storageUpdates()) {
                if (update.hasStorageSlot()) {
                    final var slot = update.storageSlotOrThrow();
                    final var oldSlotValue = store.getSlotValue(new EvmHookSlotKey(hookId, slot.key()));
                    final var oldValue = oldSlotValue == null ? null : oldSlotValue.value();
                    effectiveGas += effectiveGasCost(oldValue, slot.value());
                } else if (update.hasMappingEntries()) {
                    final var entries = update.mappingEntriesOrThrow();
                    final var p = leftPad32(entries.mappingSlot());
                    for (final var entry : entries.entries()) {
                        final var key = slotKeyOfMappingEntry(p, entry);
                        final var oldSlotValue = store.getSlotValue(new EvmHookSlotKey(hookId, key));
                        final var oldValue = oldSlotValue == null ? null : oldSlotValue.value();
                        effectiveGas += effectiveGasCost(oldValue, entry.value());
                    }
                }
            }
        } catch (Exception unexpected) {
            log.warn("Unexpected exception calculating fees for HookStore", unexpected);
            // Fallback to a mid-range default gas cost
            effectiveGas = slotCount(op.storageUpdates()) * NONZERO_INTO_NONZERO_GAS_COST;
        }
        return calculator.addGas(effectiveGas).calculate();
    }

    private int slotCount(@NonNull final List<EvmHookStorageUpdate> storageUpdates) {
        int count = 0;
        for (final var update : storageUpdates) {
            if (update.hasStorageSlot()) {
                count++;
            } else if (update.hasMappingEntries()) {
                count += update.mappingEntriesOrThrow().entries().size();
            }
        }
        return count;
    }

    private long effectiveGasCost(@Nullable final Bytes oldValue, @NonNull final Bytes newValue) {
        // We don't ever explicitly store zero; so only the null comparison
        if (oldValue == null) {
            return newValue.length() == 0 ? ZERO_INTO_ZERO_GAS_COST : NONZERO_INTO_ZERO_GAS_COST;
        } else {
            if (newValue.length() == 0) {
                return ZERO_INTO_NONZERO_GAS_COST;
            } else {
                return oldValue.equals(newValue) ? NOOP_NONZERO_INTO_NONZERO_GAS_COST : NONZERO_INTO_NONZERO_GAS_COST;
            }
        }
    }

    private void validateSlot(@NonNull final EvmHookStorageSlot slot) throws PreCheckException {
        validateWord(slot.key());
        validateWord(slot.value());
    }

    private void validateEntry(@NonNull final EvmHookMappingEntry entry) throws PreCheckException {
        validateWord(entry.value());
    }

    private void validateWord(@NonNull final Bytes bytes) throws PreCheckException {
        validateTruePreCheck(bytes.length() <= MAX_UPDATE_BYTES_LEN, EVM_HOOK_STORAGE_UPDATE_BYTES_TOO_LONG);
        final var minimalBytes = minimalRepresentationOf(bytes);
        validateTruePreCheck(bytes == minimalBytes, EVM_HOOK_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION);
    }

    /**
     * Returns the effective hook id for the given hook id.  If the given hook id uses a contract id,
     * returns a hook id that uses the account id of the contract. Otherwise, returns the given hook id.
     * @param hookId the hook id
     * @return the effective hook id
     */
    private static HookId effectiveHookId(@NonNull final HookId hookId) {
        final var entityId = hookId.entityIdOrThrow();
        return entityId.hasContractId()
                ? HookId.newBuilder()
                        .entityId(HookEntityId.newBuilder().accountId(asAccountId(entityId.contractIdOrThrow())))
                        .hookId(hookId.hookId())
                        .build()
                : hookId;
    }
}
