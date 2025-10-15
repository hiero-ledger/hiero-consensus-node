// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_DELETION_REQUIRES_ZERO_STORAGE_SLOTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.hapi.node.state.hooks.EvmHookType.LAMBDA;
import static com.hedera.node.app.hapi.utils.EntityType.HOOK;
import static com.hedera.node.app.hapi.utils.EntityType.LAMBDA_STORAGE;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.leftPad32;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.slotKeyOfMappingEntry;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STATES_STATE_ID;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.LAMBDA_STORAGE_STATE_ID;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.INSERTION;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.REMOVAL;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.UPDATE;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.ZERO_INTO_EMPTY_SLOT;
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
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Read/write access to the EVM hook states.
 */
public class WritableEvmHookStore extends ReadableEvmHookStoreImpl {
    private static final Logger log = LogManager.getLogger(WritableEvmHookStore.class);

    /**
     * We require all inputs to use minimal byte representations; but we still need to be able to distinguish
     * the cases of a {@code prev} pointer being set to {@code null} (which means "no previous slot"), versus
     * it being set to the zero key.
     */
    public static final Bytes ZERO_KEY = Bytes.fromHex("00");

    private final WritableEntityCounters entityCounters;
    private final WritableKVState<HookId, EvmHookState> hookStates;
    private final WritableKVState<LambdaSlotKey, SlotValue> storage;

    public WritableEvmHookStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states);
        this.entityCounters = requireNonNull(entityCounters);
        this.hookStates = states.get(EVM_HOOK_STATES_STATE_ID);
        this.storage = states.get(LAMBDA_STORAGE_STATE_ID);
    }

    /**
     * Puts the given slot values for the given lambda, ensuring storage linked list pointers are preserved.
     * If a new value is {@link Bytes#EMPTY}, the slot is removed.
     *
     * @param hookId the lambda ID
     * @param updates the slot updates
     * @return the net change in number of storage slots used
     * @throws HandleException if the lambda ID is not found
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
        return applyStorageMutations(hookId, keys, values);
    }

    /**
     * Puts the given single slot value for the given lambda, ensuring storage linked list pointers are preserved.
     * If the new value is {@link Bytes#EMPTY}, the slot is removed.
     *
     * @param key the slot key
     * @param value the new slot value
     * @return {@code 1} if a new slot was created, {@code -1} if an existing slot was removed,
     * or {@code 0} if an existing slot was updated or no change was made
     * @throws HandleException if the lambda ID is not found
     */
    public int updateStorage(@NonNull final LambdaSlotKey key, @NonNull final SlotValue value) {
        requireNonNull(key);
        requireNonNull(value);

        final var hookId = key.hookId();
        Bytes newValue = value.value();
        if (isAllZeroWord(newValue)) {
            // if value is empty we remove the slot
            newValue = Bytes.EMPTY;
        }
        final var minimalKey = minimalKey(key.key());
        return applyStorageMutations(hookId, List.of(minimalKey), List.of(newValue));
    }

    /**
     * Marks the given hook as deleted. We mark the hook as deleted, but do not remove it from state,
     * if there are several storage slots.
     *
     * @param hookId the lambda ID
     * @throws HandleException if the lambda ID is not found
     */
    public void remove(@NonNull final HookId hookId) {
        final var state = hookStates.get(hookId);
        validateTrue(state != null, HOOK_NOT_FOUND);
        validateTrue(state.numStorageSlots() == 0, HOOK_DELETION_REQUIRES_ZERO_STORAGE_SLOTS);
        unlinkNeighbors(state);
        hookStates.remove(hookId);
        entityCounters.decrementEntityTypeCounter(HOOK);
    }

    private void unlinkNeighbors(@NonNull final EvmHookState state) {
        final var hookId = state.hookId();
        final var prevId = state.previousHookId();
        final var nextId = state.nextHookId();

        if (prevId != null) {
            final var prev = HookId.newBuilder()
                    .hookId(prevId)
                    .entityId(hookId.entityId())
                    .build();
            final var prevState = hookStates.get(prev);
            if (prevState != null) {
                hookStates.put(prev, prevState.copyBuilder().nextHookId(nextId).build());
            } else {
                log.warn("Inconsistent state: previous hook {} not found when unlinking {}", prev, hookId);
            }
        }
        if (nextId != null) {
            final var next = HookId.newBuilder()
                    .hookId(nextId)
                    .entityId(hookId.entityId())
                    .build();
            final var nextState = hookStates.get(next);
            if (nextState != null) {
                hookStates.put(
                        next, nextState.copyBuilder().previousHookId(prevId).build());
            } else {
                log.warn("Inconsistent state: next hook {} not found when unlinking {}", next, hookId);
            }
        }
    }

    /**
     * Tries to create a new EVM hook for the given entity.
     *
     * @param creation the hook creation spec
     * @throws HandleException if the creation fails
     */
    public void createEvmHook(@NonNull final HookCreation creation) throws HandleException {
        final var details = creation.detailsOrThrow();
        final var hookId = new HookId(creation.entityIdOrThrow(), details.hookId());
        validateTrue(hookStates.get(hookId) == null, HOOK_ID_IN_USE);
        final var type =
                switch (details.hook().kind()) {
                    case LAMBDA_EVM_HOOK -> LAMBDA;
                    default -> throw new IllegalStateException("Not an EVM hook - " + creation);
                };
        final var evmHookSpec = details.lambdaEvmHookOrThrow().specOrThrow();
        final var state = EvmHookState.newBuilder()
                .hookId(hookId)
                .type(type)
                .extensionPoint(details.extensionPoint())
                .hookContractId(evmHookSpec.contractIdOrThrow())
                .firstContractStorageKey(Bytes.EMPTY)
                .previousHookId(null)
                .nextHookId(creation.nextHookId())
                .numStorageSlots(0)
                .adminKey(details.adminKey())
                .build();
        hookStates.put(hookId, state);

        // Also change the previous pointer of next hookId to this hookId
        if (creation.nextHookId() != null) {
            final var next = HookId.newBuilder()
                    .hookId(creation.nextHookId())
                    .entityId(hookId.entityId())
                    .build();
            final var nextState = hookStates.get(next);
            if (nextState != null) {
                hookStates.put(
                        next,
                        nextState.copyBuilder().previousHookId(details.hookId()).build());
            } else {
                log.warn("Inconsistent state: next hook {} not found when linking {}", next, hookId);
            }
        }
        if (type == LAMBDA) {
            final var initialUpdates = details.lambdaEvmHookOrThrow().storageUpdates();
            if (!initialUpdates.isEmpty()) {
                updateStorage(hookId, initialUpdates);
            }
        }
        entityCounters.incrementEntityTypeCount(HOOK);
    }

    public @Nullable SlotValue getOriginalSlotValue(@NonNull final LambdaSlotKey key) {
        requireNonNull(key);
        return storage.getOriginalValue(key);
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
     * Returns the set of slot keys that have been modified in this transaction.
     *
     * @return the set of modified slot keys
     */
    public Set<LambdaSlotKey> getModifiedSlotKeys() {
        return storage.modifiedKeys();
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
        final var minimalKey = minimalKey(key);
        try {
            if (!Bytes.EMPTY.equals(firstKey)) {
                updatePrevFor(new LambdaSlotKey(hookId, firstKey), minimalKey);
            }
        } catch (Exception irreparable) {
            // Since maintaining linked lists is not mission-critical, just log the error and continue
            log.error(
                    "Failed link management when inserting {}; will be unable to expire all slots for contract {}",
                    minimalKey,
                    hookId,
                    irreparable);
        }
        storage.put(new LambdaSlotKey(hookId, minimalKey), new SlotValue(value, Bytes.EMPTY, firstKey));
        return minimalKey;
    }

    public static LambdaSlotKey minimalKey(@NonNull final HookId hookId, @NonNull final Bytes key) {
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
    /**
     * Returns a minimal representation of the given key, by stripping leading zeros.
     * If the key is all zeros, returns {@link #ZERO_KEY}.
     *
     * @param key the key to minimize
     * @return the minimal representation of the key
     */
    public static Bytes minimalKey(@NonNull final Bytes key) {
        final var len = key.length();
        if (len == 0) {
            return ZERO_KEY;
        }
        int i = 0;
        while (i < len && key.getByte(i) == 0) {
            i++;
        }
        // All zeros -> ZERO_KEY, otherwise strip leading zeros
        return (i == len) ? ZERO_KEY : key.slice(i, len - i);
    }
    /**
     * Returns true if the given value is a 32-byte word with all bytes zero.
     *
     * @param val the value to check
     * @return true if the value is a 32-byte word with all bytes zero
     */
    private static boolean isAllZeroWord(@NonNull final Bytes val) {
        for (long i = 0, n = val.length(); i < n; i++) {
            if (val.getByte(i) != 0) return false;
        }
        return true;
    }

    @NonNull
    private SlotValue slotValueFor(@NonNull final LambdaSlotKey slotKey, @NonNull final String msgOnError) {
        return requireNonNull(storage.get(slotKey), () -> msgOnError + " " + slotKey.key());
    }
    /**
     * Applies the given storage mutations to the storage of the given lambda, ensuring linked list pointers
     * are preserved.
     *
     * @param hookId the lambda ID
     * @param keys the slot keys to update
     * @param values the new slot values; use {@link Bytes#EMPTY} to remove a slot
     * @return the net change in number of storage slots used
     * @throws HandleException if the lambda ID is not found
     */
    private int applyStorageMutations(
            @NonNull final HookId hookId, @NonNull final List<Bytes> keys, @NonNull final List<Bytes> values) {
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
}
