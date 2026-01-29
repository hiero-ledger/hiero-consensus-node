// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STATES_STATE_ID;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STORAGE_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.hooks.EvmHookSlotKey;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.node.app.service.contract.ReadableEvmHookStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Read-only access to EVM hook states.
 */
public class ReadableEvmHookStoreImpl implements ReadableEvmHookStore {
    private final ReadableKVState<EvmHookSlotKey, SlotValue> storage;
    private final ReadableKVState<HookId, EvmHookState> hookStates;

    public ReadableEvmHookStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.storage = states.get(EVM_HOOK_STORAGE_STATE_ID);
        this.hookStates = states.get(EVM_HOOK_STATES_STATE_ID);
    }

    public record EvmHookView(
            @NonNull EvmHookState state, @NonNull List<Slot> selectedSlots) {
        public EvmHookView {
            requireNonNull(state);
            requireNonNull(selectedSlots);
        }

        public Bytes firstStorageKey() {
            return state.firstContractStorageKey();
        }
    }

    public record Slot(
            @NonNull EvmHookSlotKey key, @Nullable SlotValue value) {
        public Slot {
            requireNonNull(key);
        }

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
    @Override
    public @Nullable EvmHookState getEvmHook(@NonNull final HookId hookId) {
        requireNonNull(hookId);
        return hookStates.get(hookId);
    }

    public @Nullable SlotValue getSlotValue(@NonNull final EvmHookSlotKey key) {
        requireNonNull(key);
        return storage.get(key);
    }
}
