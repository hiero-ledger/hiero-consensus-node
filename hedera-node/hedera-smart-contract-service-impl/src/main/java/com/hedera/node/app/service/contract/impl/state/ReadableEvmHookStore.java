// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.STORAGE_KEY;
import static com.hedera.node.app.service.contract.impl.schemas.V061ContractSchema.EVM_HOOK_STATES_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.state.hooks.LambdaSlotKey;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only access to lambda states.
 */
public class ReadableEvmHookStore {
    private final ReadableKVState<LambdaSlotKey, SlotValue> storage;
    private final ReadableKVState<HookId, EvmHookState> hookStates;

    public ReadableEvmHookStore(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.storage = states.get(STORAGE_KEY);
        this.hookStates = states.get(EVM_HOOK_STATES_KEY);
    }

    public record EvmHookView(@NonNull EvmHookState state, @NonNull List<Slot> selectedSlots) {
        public ContractID contractId() {
            return state.hookContractIdOrThrow();
        }

        public Bytes firstStorageKey() {
            return state.firstContractStorageKey();
        }
    }

    public record Slot(@NonNull LambdaSlotKey key, @Nullable SlotValue value) {
        @Nullable
        public Bytes maybeBytesValue() {
            return (value == null || Bytes.EMPTY.equals(value.value())) ? null : value.value();
        }

        public @NonNull Bytes effectivePrevKey() {
            return value == null ? Bytes.EMPTY : value.previousKey();
        }

        public @NonNull Bytes effectiveNextKey() {
            return value == null ? Bytes.EMPTY : value.nextKey();
        }
    }

    /**
     * Returns the EVM hook state for the given hook ID.
     * @param hookId the hook ID
     * @return the EVM hook state, or null if not found
     */
    public @Nullable EvmHookState getEvmHook(@NonNull final HookId hookId) {
        requireNonNull(hookId);
        return hookStates.get(hookId);
    }

    /**
     * Returns a list of slot values for the given hook and keys.
     * @param hookId the hook ID
     * @param keys the keys
     * @return a list of slots
     * @throws HandleException if the hook
     */
    public EvmHookView getView(@NonNull final HookId hookId, @NonNull final List<Bytes> keys) throws HandleException {
        requireNonNull(hookId);
        requireNonNull(keys);
        final var state = hookStates.get(hookId);
        if (state == null) {
            throw new HandleException(HOOK_NOT_FOUND);
        }
        final List<Slot> slots = new ArrayList<>(keys.size());
        keys.forEach(key -> {
            final var slotKey = new LambdaSlotKey(hookId, zeroPaddedTo32(key));
            final var slotValue = storage.get(slotKey);
            slots.add(new Slot(slotKey, slotValue));
        });
        return new EvmHookView(state, slots);
    }

    protected static Bytes zeroPaddedTo32(@NonNull final Bytes bytes) {
        final int len = (int) bytes.length();
        if (len == 32L) {
            return bytes;
        } else {
            final var padded = new byte[32];
            System.arraycopy(bytes.toByteArray(), 0, padded, 32 - len, len);
            return Bytes.wrap(padded);
        }
    }
}
