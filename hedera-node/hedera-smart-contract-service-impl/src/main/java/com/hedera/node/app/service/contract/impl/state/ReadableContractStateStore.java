// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema;
import com.hedera.node.app.service.entityid.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.Set;

/**
 * A read-only {@link ContractStateStore}.
 */
@SuppressWarnings("MissingJavadoc")
public class ReadableContractStateStore implements ContractStateStore {

    private final ReadableKVState<SlotKey, SlotValue> storage;
    private final ReadableKVState<ContractID, Bytecode> bytecode;
    private final ReadableEntityCounters entityCounters;

    public ReadableContractStateStore(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        requireNonNull(states);
        this.entityCounters = requireNonNull(entityCounters);
        this.storage = states.get(V0490ContractSchema.STORAGE_STATE_ID);
        this.bytecode = states.get(V0490ContractSchema.BYTECODE_STATE_ID);
    }

    @Override
    public Bytecode getBytecode(@NonNull ContractID contractId) {
        return bytecode.get(contractId);
    }

    /**
     * Refuses to put bytecode.
     *
     * @param contractId the contract id to put the {@link Bytecode} for
     * @param code the {@link Bytecode} to put
     * @throws UnsupportedOperationException always
     */
    @Override
    public void putBytecode(@NonNull final ContractID contractId, @NonNull final Bytecode code) {
        throw new UnsupportedOperationException("Cannot put bytecode in a read-only store");
    }

    /**
     * Refuses to remove slots.
     *
     * @param key the {@link SlotKey} to remove
     * @throws UnsupportedOperationException always
     */
    @Override
    public void removeSlot(@NonNull final SlotKey key) {
        throw new UnsupportedOperationException("Cannot remove slots from a read-only store");
    }

    @Override
    public void adjustSlotCount(final long delta) {
        throw new UnsupportedOperationException("Cannot adjust slot count in a read-only store");
    }

    /**
     * Refuses to put slots.
     *
     * @param key the {@link SlotKey} to put the {@link SlotValue} for
     * @param value the {@link SlotValue} to put
     * @throws UnsupportedOperationException always
     */
    @Override
    public void putSlot(@NonNull final SlotKey key, @NonNull final SlotValue value) {
        throw new UnsupportedOperationException("Cannot put slots in a read-only store");
    }

    /**
     * Returns an empty set of modified slot keys.
     *
     * @return an empty set of modified slot keys
     */
    @Override
    public Set<SlotKey> getModifiedSlotKeys() {
        return Collections.emptySet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable SlotValue getSlotValue(@NonNull final SlotKey key) {
        return storage.get(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable SlotValue getOriginalSlotValue(@NonNull SlotKey key) {
        return storage.get(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNumSlots() {
        return entityCounters.getCounterFor(EntityType.CONTRACT_STORAGE);
    }

    @Override
    public long getNumBytecodes() {
        return entityCounters.getCounterFor(EntityType.CONTRACT_BYTECODE);
    }
}
