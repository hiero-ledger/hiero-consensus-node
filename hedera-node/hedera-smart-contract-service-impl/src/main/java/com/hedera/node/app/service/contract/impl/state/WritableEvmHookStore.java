// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.hapi.node.state.hooks.EvmHookType.LAMBDA;
import static com.hedera.hapi.node.state.hooks.EvmHookType.PURE;
import static com.hedera.node.app.hapi.utils.EntityType.HOOK;
import static com.hedera.node.app.hapi.utils.EntityType.LAMBDA_STORAGE;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STATES_KEY;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.LAMBDA_STORAGE_KEY;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.INSERTION;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.REMOVAL;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.UPDATE;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.ZERO_INTO_EMPTY_SLOT;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.leftPad32;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.slotKeyOfMappingEntry;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.LambdaStorageUpdate;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.hooks.EvmHookState;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Read/write access to the EVM hook states.
 */
public class WritableEvmHookStore extends ReadableEvmHookStore {
    private static final Logger log = LogManager.getLogger(WritableEvmHookStore.class);

    /**
     * We require all inputs to use minimal byte representations; but we still need to be able to distinguish
     * the cases of a {@code prev} pointer being set to {@code null} (which means "no previous slot"), versus
     * it being set to the zero key.
     */
    private static final Bytes ZERO_KEY = Bytes.fromHex("00");

    private final WritableEntityCounters entityCounters;
    private final WritableKVState<HookId, EvmHookState> hookStates;
    private final WritableKVState<LambdaSlotKey, SlotValue> storage;

    public WritableEvmHookStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states);
        this.entityCounters = requireNonNull(entityCounters);
        this.hookStates = states.get(EVM_HOOK_STATES_KEY);
        this.storage = states.get(LAMBDA_STORAGE_KEY);
    }

    /**
     * Puts the given slot values for the given lambda, ensuring storage linked list pointers are preserved.
     * If a new value is {@link Bytes#EMPTY}, the slot is removed.
     *
     * @param hookId the lambda ID
     * @param updates the slot updates
     * @throws HandleException if the lambda ID is not found
     * @return the net change in number of storage slots used
     */
    public int updateStorage(@NonNull final HookId hookId, @NonNull final List<LambdaStorageUpdate> updates)
            throws HandleException {
        final List<Bytes> keys = new ArrayList<>(updates.size());
        final List<Bytes> values = new ArrayList<>(updates.size());
        for (final var update : updates) {
            if (update.hasStorageSlot()) {
                final var slot = update.storageSlotOrThrow();
                keys.add(slot.key());
                values.add(slot.value());
            } else {
                final var entries = update.mappingEntriesOrThrow();
                final var p = leftPad32(entries.mappingSlot());
                for (final var entry : entries.entries()) {
                    keys.add(slotKeyOfMappingEntry(p, entry));
                    values.add(entry.value());
                }
            }
        }
        final var view = getView(hookId, keys);
        var firstKey = view.firstStorageKey();
        int removals = 0;
        int insertions = 0;
        for (int i = 0, n = keys.size(); i < n; i++) {
            final var slot = view.selectedSlots().get(i);
            final var update = SlotUpdate.from(slot, values.get(i));
            firstKey = switch (update.asAccessType()) {
                case REMOVAL -> {
                    removals++;
                    yield removeSlot(hookId, firstKey, update.key());
                }
                case INSERTION -> {
                    insertions++;
                    yield insertSlot(hookId, firstKey, update.key(), update.newValueOrThrow());
                }
                case UPDATE -> {
                    final var slotValue =
                            new SlotValue(update.newValueOrThrow(), slot.effectivePrevKey(), slot.effectiveNextKey());
                    storage.put(slot.key(), slotValue);
                    yield firstKey;
                }
                default -> firstKey;
            };
        }
        if (insertions != 0 || removals != 0) {
            final int delta = insertions - removals;
            entityCounters.adjustEntityCount(LAMBDA_STORAGE, delta);
            final var hookState = view.state();
            hookStates.put(
                    hookId,
                    hookState
                            .copyBuilder()
                            .firstContractStorageKey(firstKey)
                            .numStorageSlots(hookState.numStorageSlots() + delta)
                            .build());
            return delta;
        }
        return 0;
    }

    /**
     * Marks the given hook as deleted.
     * @param hookId the lambda ID
     * @throws HandleException if the lambda ID is not found
     */
    public void markDeleted(@NonNull final HookId hookId) {
        final var state = hookStates.get(hookId);
        validateTrue(state != null, HOOK_NOT_FOUND);
        hookStates.put(hookId, state.copyBuilder().deleted(true).build());
    }

    /**
     * Tries to create a new EVM hook for the given entity.
     * @param creation the hook creation spec
     * @throws HandleException if the creation fails
     */
    public void createEvmHook(@NonNull final HookCreation creation) throws HandleException {
        final var details = creation.detailsOrThrow();
        final var hookId = new HookId(creation.entityIdOrThrow(), details.hookId());
        validateTrue(hookStates.get(hookId) == null, HOOK_ID_IN_USE);
        final var type =
                switch (details.hook().kind()) {
                    case PURE_EVM_HOOK -> PURE;
                    case LAMBDA_EVM_HOOK -> LAMBDA;
                    default -> throw new IllegalStateException("Not an EVM hook - " + creation);
                };
        final var evmHookSpec = type == PURE
                ? details.pureEvmHookOrThrow().specOrThrow()
                : details.lambdaEvmHookOrThrow().specOrThrow();
        final var state = EvmHookState.newBuilder()
                .hookId(hookId)
                .type(type)
                .extensionPoint(details.extensionPoint())
                .hookContractId(evmHookSpec.contractIdOrThrow())
                .deleted(false)
                .firstContractStorageKey(Bytes.EMPTY)
                .previousHookId(null)
                .nextHookId(creation.nextHookId())
                .numStorageSlots(0)
                .adminKey(details.adminKey())
                .build();
        hookStates.put(hookId, state);
        if (type == LAMBDA) {
            final var initialUpdates = details.lambdaEvmHookOrThrow().storageUpdates();
            if (!initialUpdates.isEmpty()) {
                final int delta = updateStorage(hookId, initialUpdates);
                if (delta != 0) {
                    entityCounters.adjustEntityCount(LAMBDA_STORAGE, delta);
                }
            }
        }
        entityCounters.incrementEntityTypeCount(HOOK);
    }

    private record SlotUpdate(@NonNull Bytes key, @Nullable Bytes oldValue, @Nullable Bytes newValue) {
        public static SlotUpdate from(@NonNull final Slot slot, @NonNull final Bytes value) {
            return new SlotUpdate(slot.key().key(), slot.maybeBytesValue(), Bytes.EMPTY.equals(value) ? null : value);
        }

        public @NonNull Bytes newValueOrThrow() {
            return requireNonNull(newValue);
        }

        public StorageAccessType asAccessType() {
            if (oldValue == null) {
                return newValue == null ? ZERO_INTO_EMPTY_SLOT : INSERTION;
            } else {
                return newValue == null ? REMOVAL : UPDATE;
            }
        }
    }

    /**
     * Removes the given key from the slot storage and from the linked list of storage for the given contract.
     *
     * @param hookId The id of the lambda whose storage is being updated
     * @param firstKey The first key in the linked list of storage for the given contract
     * @param key The slot key to remove
     * @return the new first key in the linked list of storage for the given contract
     */
    @NonNull
    private Bytes removeSlot(@NonNull final HookId hookId, @NonNull Bytes firstKey, @NonNull final Bytes key) {
        requireNonNull(firstKey);
        requireNonNull(hookId);
        requireNonNull(key);
        final var slotKey = new LambdaSlotKey(hookId, key);
        try {
            final var slotValue = slotValueFor(slotKey, "Missing key");
            final var nextKey = slotValue.nextKey();
            final var prevKey = slotValue.previousKey();
            if (!Bytes.EMPTY.equals(nextKey)) {
                updatePrevFor(new LambdaSlotKey(hookId, nextKey), prevKey);
            }
            if (!Bytes.EMPTY.equals(prevKey)) {
                updateNextFor(new LambdaSlotKey(hookId, prevKey), nextKey);
            }
            firstKey = key.equals(firstKey) ? nextKey : firstKey;
        } catch (Exception irreparable) {
            // Since maintaining linked lists is not mission-critical, just log the error and continue
            log.error(
                    "Failed link management when removing {}; will be unable to expire all slots for hook {}",
                    key,
                    hookId,
                    irreparable);
        }
        storage.remove(slotKey);
        return firstKey;
    }

    /**
     * Inserts the given key into the slot storage and into the linked list of storage for the given contract.
     *
     * @param hookId The contract id under consideration
     * @param firstKey The first key in the linked list of storage for the given contract
     * @param key The slot key to insert
     * @param value The new value for the slot
     * @return the new first key in the linked list of storage for the given contract
     */
    @NonNull
    private Bytes insertSlot(
            @NonNull final HookId hookId,
            @NonNull final Bytes firstKey,
            @NonNull final Bytes key,
            @NonNull final Bytes value) {
        requireNonNull(key);
        requireNonNull(value);
        try {
            if (!Bytes.EMPTY.equals(firstKey)) {
                updatePrevFor(new LambdaSlotKey(hookId, firstKey), key);
            }
        } catch (Exception irreparable) {
            // Since maintaining linked lists is not mission-critical, just log the error and continue
            log.error(
                    "Failed link management when inserting {}; will be unable to expire all slots for contract {}",
                    key,
                    hookId,
                    irreparable);
        }
        storage.put(minimalKey(hookId, key), new SlotValue(value, Bytes.EMPTY, firstKey));
        return key;
    }

    private LambdaSlotKey minimalKey(@NonNull final HookId hookId, @NonNull final Bytes key) {
        return new LambdaSlotKey(hookId, minimalKey(key));
    }

    private void updatePrevFor(@NonNull final LambdaSlotKey key, @NonNull final Bytes newPrevKey) {
        final var value = slotValueFor(key, "Missing next key");
        storage.put(key, value.copyBuilder().previousKey(newPrevKey).build());
    }

    private void updateNextFor(@NonNull final LambdaSlotKey key, @NonNull final Bytes newNextKey) {
        final var value = slotValueFor(key, "Missing prev key");
        storage.put(key, value.copyBuilder().nextKey(newNextKey).build());
    }

    private Bytes minimalKey(@NonNull final Bytes key) {
        return Bytes.EMPTY.equals(key) ? ZERO_KEY : key;
    }

    @NonNull
    private SlotValue slotValueFor(@NonNull final LambdaSlotKey slotKey, @NonNull final String msgOnError) {
        return requireNonNull(storage.get(slotKey), () -> msgOnError + " " + slotKey.key());
    }
}
