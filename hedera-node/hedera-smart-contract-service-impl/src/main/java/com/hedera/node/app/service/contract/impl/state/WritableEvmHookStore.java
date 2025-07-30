// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.node.app.hapi.utils.EntityType.HOOK;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STATES_KEY;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.LAMBDA_STORAGE_KEY;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.INSERTION;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.REMOVAL;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.UPDATE;
import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.ZERO_INTO_EMPTY_SLOT;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.CreatedHookId;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.LambdaStorageUpdate;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Read/write access to lambda states.
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
    private final WritableKVState<CreatedHookId, EvmHookState> hookStates;
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
     */
    public void updateSlots(@NonNull final CreatedHookId hookId, @NonNull final List<LambdaStorageUpdate> updates)
            throws HandleException {
        final List<Bytes> keys = new ArrayList<>(updates.size());
        for (final var update : updates) {
            if (update.hasStorageSlot()) {
                keys.add(update.storageSlotOrThrow().key());
            } else {
                throw new AssertionError("Not implemented");
            }
        }
        final var view = getView(hookId, keys);
        var firstKey = view.firstStorageKey();
        int removals = 0;
        int insertions = 0;
        for (int i = 0, n = keys.size(); i < n; i++) {
            final var slot = view.selectedSlots().get(i);
            final var update = SlotUpdate.from(slot, updates.get(i));
            firstKey = switch (update.asAccessType()) {
                case REMOVAL -> {
                    removals++;
                    yield removeSlot(firstKey, hookId, update.key());
                }
                case INSERTION -> {
                    insertions++;
                    yield insertSlot(firstKey, hookId, update.key(), update.newValueOrThrow());
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
            final var hookState = view.state();
            hookStates.put(
                    hookId,
                    hookState
                            .copyBuilder()
                            .firstContractStorageKey(firstKey)
                            .numStorageSlots(hookState.numStorageSlots() + insertions - removals)
                            .build());
        }
    }

    /**
     * Marks the lambda as deleted.
     *
     * @param hookId the lambda ID
     * @throws HandleException if the lambda ID is not found
     */
    public void markDeleted(@NonNull final CreatedHookId hookId) {
        final var state = hookStates.get(hookId);
        validateTrue(state != null, HOOK_NOT_FOUND);
        hookStates.put(hookId, state.copyBuilder().deleted(true).build());
    }

    /**
     * Tries to create a new EVM hook for the given entity.
     * @param entityId the entity ID
     * @param creation the hook creation spec
     * @throws HandleException if the creation fails
     */
    public void createEvmHook(
            @NonNull final HookEntityId entityId, @NonNull final HookCreation creation, final long nextHookId)
            throws HandleException {
        final var details = creation.detailsOrThrow();
        final var hookId = new CreatedHookId(entityId, details.hookId());
        validateTrue(hookStates.get(hookId) == null, HOOK_ID_IN_USE);
        final var type =
                switch (details.hook().kind()) {
                    case PURE_EVM_HOOK -> EvmHookType.PURE;
                    case LAMBDA_EVM_HOOK -> EvmHookType.LAMBDA;
                    default -> throw new IllegalStateException("Not an EVM hook - " + creation);
                };
        final var evmHookSpec = type == EvmHookType.PURE
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
                .nextHookId(nextHookId)
                .numStorageSlots(0)
                .build();
        hookStates.put(hookId, state);
        if (type == EvmHookType.LAMBDA) {
            final var initialUpdates = details.lambdaEvmHookOrThrow().storageUpdates();
            if (!initialUpdates.isEmpty()) {
                updateSlots(hookId, initialUpdates);
            }
        }
        entityCounters.incrementEntityTypeCount(HOOK);
    }

    private record SlotUpdate(@NonNull Bytes key, @Nullable Bytes oldValue, @Nullable Bytes newValue) {
        public static SlotUpdate from(@NonNull final Slot slot, @NonNull final LambdaStorageUpdate update) {
            if (update.hasStorageSlot()) {
                final var value = update.storageSlotOrThrow().value();
                return new SlotUpdate(
                        slot.key().key(), slot.maybeBytesValue(), Bytes.EMPTY.equals(value) ? null : value);
            } else {
                throw new AssertionError("Not implemented");
            }
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

    /**
     * Removes the given key from the slot storage and from the linked list of storage for the given contract.
     * @param firstStorageKey The first key in the linked list of storage for the given contract
     * @param hookId The id of the lambda whose storage is being updated
     * @param key The slot key to remove
     * @return the new first key in the linked list of storage for the given contract
     */
    @NonNull
    private Bytes removeSlot(
            @NonNull Bytes firstStorageKey, @NonNull final CreatedHookId hookId, @NonNull final Bytes key) {
        requireNonNull(firstStorageKey);
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
            firstStorageKey = key.equals(firstStorageKey) ? nextKey : firstStorageKey;
        } catch (Exception irreparable) {
            // Since maintaining linked lists is not mission-critical, just log the error and continue
            log.error(
                    "Failed link management when removing {}; will be unable to" + " expire all slots for hook {}",
                    key,
                    hookId,
                    irreparable);
        }
        storage.remove(slotKey);
        return firstStorageKey;
    }

    /**
     * Inserts the given key into the slot storage and into the linked list of storage for the given contract.
     *
     * @param firstStorageKey The first key in the linked list of storage for the given contract
     * @param hookId The contract id under consideration
     * @param newKey The slot key to insert
     * @param newValue The new value for the slot
     * @return the new first key in the linked list of storage for the given contract
     */
    @NonNull
    private Bytes insertSlot(
            @NonNull final Bytes firstStorageKey,
            @NonNull final CreatedHookId hookId,
            @NonNull final Bytes newKey,
            @NonNull final Bytes newValue) {
        requireNonNull(newKey);
        requireNonNull(newValue);
        try {
            if (!Bytes.EMPTY.equals(firstStorageKey)) {
                updatePrevFor(new LambdaSlotKey(hookId, firstStorageKey), newKey);
            }
        } catch (Exception irreparable) {
            // Since maintaining linked lists is not mission-critical, just log the error and continue
            log.error(
                    "Failed link management when inserting {}; will be unable to" + " expire all slots for contract {}",
                    newKey,
                    hookId,
                    irreparable);
        }
        storage.put(minimalKey(hookId, newKey), new SlotValue(newValue, Bytes.EMPTY, firstStorageKey));
        return newKey;
    }

    private LambdaSlotKey minimalKey(@NonNull final CreatedHookId hookId, @NonNull final Bytes key) {
        return new LambdaSlotKey(hookId, minimal(key));
    }

    private void updatePrevFor(@NonNull final LambdaSlotKey key, @NonNull final Bytes newPrevKey) {
        final var value = slotValueFor(key, "Missing next key");
        storage.put(key, value.copyBuilder().previousKey(newPrevKey).build());
    }

    private void updateNextFor(@NonNull final LambdaSlotKey key, @NonNull final Bytes newNextKey) {
        final var value = slotValueFor(key, "Missing prev key");
        storage.put(key, value.copyBuilder().nextKey(newNextKey).build());
    }

    private Bytes minimal(Bytes key) {
        return Bytes.EMPTY.equals(key) ? ZERO_KEY : key;
    }

    @NonNull
    private SlotValue slotValueFor(@NonNull final LambdaSlotKey slotKey, @NonNull final String msgOnError) {
        return requireNonNull(storage.get(slotKey), () -> msgOnError + " " + slotKey.key());
    }
}
