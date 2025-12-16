// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;

/**
 * An intermediary that manages access to the slot and bytecode key/value states.
 */
public interface ContractStateStore {
    /**
     * Returns the {@link Bytecode} for the given contract number.
     *
     * @param contractID the contract id to get the {@link Bytecode} for
     * @return the {@link Bytecode} for the given contract number
     */
    Bytecode getBytecode(@NonNull ContractID contractID);

    /**
     * Puts the given {@link Bytecode} for the given contract number.
     *
     * @param contractID the contract id to put the {@link Bytecode} for
     * @param code the {@link Bytecode} to put
     */
    void putBytecode(@NonNull ContractID contractID, @NonNull Bytecode code);

    /**
     * Removes the given {@link SlotKey}.
     *
     * @param key the {@link SlotKey} to remove
     */
    void removeSlot(@NonNull SlotKey key);

    /**
     * Adjusts the slot count by the given delta in the {@link WritableEntityCounters}.
     * @param delta the delta to adjust the slot count by
     */
    void adjustSlotCount(final long delta);

    /**
     * Puts the given {@link SlotValue} for the given {@link SlotKey}.
     *
     * <p><b>Note: </b>Putting a {@link SlotValue#value()} of binary zeros is <b>not</b>
     * equivalent to calling {@link #removeSlot(SlotKey)}. We defer removing slots until
     * the very end of a contract transaction, at the point in
     * {@link RootProxyWorldUpdater#commit()} where we know we have fixed up the
     * {@link SlotValue#previousKey()} and {@link SlotValue#nextKey()} pointers.
     *
     * @param key the {@link SlotKey} to put the {@link SlotValue} for
     * @param value the {@link SlotValue} to put
     */
    void putSlot(@NonNull SlotKey key, @NonNull SlotValue value);

    /**
     * Returns the {@link Set} of {@link SlotKey}s that have been modified.
     *
     * @return the {@link Set} of {@link SlotKey}s that have been modified
     */
    Set<SlotKey> getModifiedSlotKeys();

    /**
     * Returns the {@link SlotValue} for the given {@link SlotKey}, or null if not found.
     *
     * @param key the {@link SlotKey} to get the {@link SlotValue} for
     * @return the {@link SlotValue} for the given {@link SlotKey}, or null if not found
     */
    @Nullable
    SlotValue getSlotValue(@NonNull SlotKey key);

    /**
     * Returns the original {@link SlotValue} for the given {@link SlotKey}, or null if not found.
     *
     * @param key the {@link SlotKey} to get the {@link SlotValue} for
     * @return the original {@link SlotValue} for the given {@link SlotKey}, or null if not found
     */
    @Nullable
    SlotValue getOriginalSlotValue(@NonNull SlotKey key);

    /**
     * Returns the number of slots.
     *
     * @return the number of slots
     */
    long getNumSlots();

    /**
     * Returns the number of bytecodes.
     *
     * @return the number of bytecodes
     */
    long getNumBytecodes();
}
