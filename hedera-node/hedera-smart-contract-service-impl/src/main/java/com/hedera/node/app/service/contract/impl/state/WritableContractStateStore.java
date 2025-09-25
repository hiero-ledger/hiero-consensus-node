// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;

/**
 * A fully mutable {@link ContractStateStore}.
 */
public class WritableContractStateStore extends ReadableContractStateStore implements ContractStateStore {

    private final WritableKVState<SlotKey, SlotValue> storage;
    private final WritableKVState<ContractID, Bytecode> bytecode;
    private final WritableEntityCounters entityCounters;

    /**
     * Create a new {@link WritableContractStateStore} instance.
     *
     * @param states The state to use.
     */
    public WritableContractStateStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        requireNonNull(states);
        this.storage = states.get(V0490ContractSchema.STORAGE_STATE_ID);
        this.bytecode = states.get(V0490ContractSchema.BYTECODE_STATE_ID);
        this.entityCounters = requireNonNull(entityCounters);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Bytecode getBytecode(@NonNull final ContractID contractID) {
        return bytecode.get(requireNonNull(contractID));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putBytecode(@NonNull final ContractID contractID, @NonNull final Bytecode code) {
        bytecode.put(requireNonNull(contractID), requireNonNull(code));
        entityCounters.incrementEntityTypeCount(EntityType.CONTRACT_BYTECODE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeSlot(@NonNull final SlotKey key) {
        storage.remove(requireNonNull(key));
    }

    @Override
    public void adjustSlotCount(final long delta) {
        entityCounters.adjustEntityCount(EntityType.CONTRACT_STORAGE, delta);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putSlot(@NonNull final SlotKey key, @NonNull final SlotValue value) {
        storage.put(requireNonNull(key), requireNonNull(value));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<SlotKey> getModifiedSlotKeys() {
        return storage.modifiedKeys();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable SlotValue getSlotValue(@NonNull final SlotKey key) {
        return storage.get(requireNonNull(key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable SlotValue getOriginalSlotValue(@NonNull final SlotKey key) {
        return storage.getOriginalValue(requireNonNull(key));
    }
}
