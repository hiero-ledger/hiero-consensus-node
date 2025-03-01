// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_INDEX_IN_USE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.node.app.hapi.utils.EntityType.HOOK;
import static com.hedera.node.app.service.contract.impl.infra.IterableStorageManager.insertAccessedValue;
import static com.hedera.node.app.service.contract.impl.infra.IterableStorageManager.removeAccessedValue;
import static com.hedera.node.app.service.contract.impl.schemas.V061ContractSchema.EVM_HOOK_STATES_KEY;
import static com.hedera.node.app.service.contract.impl.schemas.V061ContractSchema.LAMBDA_STORAGE_KEY;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.INSERTION;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.REMOVAL;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.UPDATE;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.ZERO_INTO_EMPTY_SLOT;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.HookInstallerId;
import com.hedera.hapi.node.hooks.HookInstall;
import com.hedera.hapi.node.hooks.LambdaStorageSlot;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.state.hooks.EvmHookType;
import com.hedera.hapi.node.state.hooks.LambdaSlotKey;
import com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Read/write access to lambda states.
 */
public class WritableEvmHookStore extends ReadableEvmHookStore {
    private final ContractStateStore stateStore;
    private final WritableEntityCounters entityCounters;
    private final WritableKVState<HookId, EvmHookState> hookStates;
    private final WritableKVState<LambdaSlotKey, SlotValue> storage;

    public WritableEvmHookStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states);
        this.stateStore = new WritableContractStateStore(states, entityCounters);
        this.entityCounters = requireNonNull(entityCounters);
        this.hookStates = states.get(EVM_HOOK_STATES_KEY);
        this.storage = states.get(LAMBDA_STORAGE_KEY);
    }

    /**
     * Puts the given slot values for the given lambda, ensuring storage linked list pointers are preserved.
     * If a new value is {@link Bytes#EMPTY}, the slot is removed.
     *
     * @param hookId the lambda ID
     * @param slots the slot updates
     * @throws HandleException if the lambda ID is not found
     */
    public void updateSlots(@NonNull final HookId hookId, @NonNull final List<LambdaStorageSlot> slots)
            throws HandleException {
        final List<Bytes> keys = new ArrayList<>(slots.size());
        for (final var slot : slots) {
            keys.add(slot.key());
        }
        final var view = getView(hookId, keys);
        final var contractId = view.contractId();
        var firstKey = view.firstStorageKey();
        int slotUsageChange = 0;
        for (int i = 0, n = keys.size(); i < n; i++) {
            final var slot = view.selectedSlots().get(i);
            final var update = SlotUpdate.from(slot, slots.get(i).value());
            firstKey = switch (update.asAccessType()) {
                case REMOVAL -> {
                    slotUsageChange--;
                    yield removeAccessedValue(stateStore, firstKey, contractId, update.key());
                }
                case INSERTION -> {
                    slotUsageChange++;
                    yield insertAccessedValue(stateStore, firstKey, update.newValueOrThrow(), contractId, update.key());
                }
                case UPDATE -> {
                    final var slotValue =
                            new SlotValue(update.newValueOrThrow(), slot.effectivePrevKey(), slot.effectiveNextKey());
                    storage.put(slot.key(), slotValue);
                    yield firstKey;
                }
                default -> firstKey;};
        }
        if (slotUsageChange != 0) {
            final var oldState = view.state();
            hookStates.put(
                    hookId,
                    oldState.copyBuilder()
                            .firstContractStorageKey(firstKey)
                            .numStorageSlots(oldState.numStorageSlots() + slotUsageChange)
                            .build());
            stateStore.adjustSlotCount(slotUsageChange);
        }
    }

    /**
     * Marks the lambda as deleted.
     *
     * @param hookId the lambda ID
     * @throws HandleException if the lambda ID is not found
     */
    public void markDeleted(@NonNull final HookId hookId) {
        final var state = hookStates.get(hookId);
        validateTrue(state != null, HOOK_NOT_FOUND);
        hookStates.put(hookId, state.copyBuilder().deleted(true).build());
    }

    /**
     * Tries to install a new lambda with the given id.
     *
     * @param installerId the installer ID
     * @param install the installation
     * @throws HandleException if the installation is invalid
     */
    public void installEvmHook(
            final long nextIndex, @NonNull final HookInstallerId installerId, @NonNull final HookInstall install)
            throws HandleException {
        final var hookId = new HookId(installerId, install.index());
        validateTrue(hookStates.get(hookId) == null, HOOK_INDEX_IN_USE);
        final var type =
                switch (install.hook().kind()) {
                    case PURE_EVM_HOOK -> EvmHookType.PURE;
                    case LAMBDA_EVM_HOOK -> EvmHookType.LAMBDA;
                    default -> throw new IllegalStateException("Not an EVM hook - " + install);
                };
        final var evmHookSpec = type == EvmHookType.PURE
                ? install.pureEvmHookOrThrow().specOrThrow()
                : install.lambdaEvmHookOrThrow().specOrThrow();
        final var state = EvmHookState.newBuilder()
                .hookId(hookId)
                .type(type)
                .extensionPoint(install.extensionPoint())
                .hookContractId(evmHookSpec.contractIdOrThrow())
                .defaultGasLimit(evmHookSpec.defaultGasLimit())
                .chargingSpec(install.chargingSpecOrThrow())
                .deleted(false)
                .firstContractStorageKey(Bytes.EMPTY)
                .previousIndex(0L)
                .nextIndex(nextIndex)
                .numStorageSlots(0)
                .build();
        hookStates.put(hookId, state);
        if (type == EvmHookType.LAMBDA) {
            final var initialStorageSlots = install.lambdaEvmHookOrThrow().storageSlots();
            if (!initialStorageSlots.isEmpty()) {
                updateSlots(hookId, initialStorageSlots);
            }
        }
        entityCounters.incrementEntityTypeCount(HOOK);
    }

    private record SlotUpdate(@NonNull Bytes key, @Nullable Bytes oldValue, @Nullable Bytes newValue) {
        public static SlotUpdate from(@NonNull final Slot slot, @NonNull final Bytes value) {
            return new SlotUpdate(slot.key().key(), slot.maybeBytesValue(), Bytes.EMPTY.equals(value) ? null : value);
        }

        public @NonNull Bytes newValueOrThrow() {
            return zeroPaddedTo32(requireNonNull(newValue));
        }

        public StorageAccessType asAccessType() {
            if (oldValue == null) {
                return newValue == null ? ZERO_INTO_EMPTY_SLOT : INSERTION;
            } else {
                return newValue == null ? REMOVAL : UPDATE;
            }
        }
    }
}
