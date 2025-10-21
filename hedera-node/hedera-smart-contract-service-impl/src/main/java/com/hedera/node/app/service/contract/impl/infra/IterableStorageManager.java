// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.infra;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.service.token.HookDispatchUtils.HTS_HOOKS_CONTRACT_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.node.app.service.contract.impl.state.StorageSizeChange;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides the logic for maintaining per-contract linked lists of owned storage, and keeping the
 * number of slots used per contract up to date; i.e., the logic for keeping per-contract storage
 * "legible" even though all slots are stored in a single map.
 */
@Singleton
public class IterableStorageManager {
    private static final Logger log = LogManager.getLogger(IterableStorageManager.class);

    @Inject
    public IterableStorageManager() {
        // Dagger2
    }

    /**
     * Given a writable storage K/V state and the pending changes to storage values and sizes made in this
     * scope, "rewrites" the pending changes to maintain per-contract linked lists of owned storage. (The
     * linked lists are used to purge all the contract's storage from state when it expires.)
     *
     * <p>Besides updating the first keys of these linked lists in the scoped accounts, also updates the
     * slots used per contract via
     * {@link HandleHederaOperations#updateStorageMetadata(ContractID, Bytes, int)}.
     *
     * @param enhancement the enhancement for the current transaction
     * @param allAccesses the pending changes to storage values
     * @param allSizeChanges the pending changes to storage sizes
     * @param store the writable state store
     */
    public void persistChanges(
            @NonNull final Enhancement enhancement,
            @NonNull final List<StorageAccesses> allAccesses,
            @NonNull final List<StorageSizeChange> allSizeChanges,
            @NonNull final ContractStateStore store) {
        // map to store the first storage key for each contract
        final Map<ContractID, Bytes> firstKeys = new HashMap<>();

        // Adjust the storage linked lists for each contract
        allAccesses.forEach(contractAccesses -> contractAccesses.accesses().forEach(access -> {
            if (access.isUpdate()) {
                final var contractId = contractAccesses.contractID();
                if (contractId.equals(HTS_HOOKS_CONTRACT_ID)) {
                    // Skip managing linked list for 0x16d, as its storage is managed separately
                    return;
                }
                // If we have already changed the head pointer for this contract,
                // use that; otherwise, get the contract's head pointer from state
                final var firstContractKey =
                        firstKeys.computeIfAbsent(contractId, cid -> contractFirstKeyOf(enhancement, contractId));

                // Only certain access types can change the head slot in a contract's storage linked list
                final var newFirstContractKey =
                        switch (StorageAccessType.getAccessType(access)) {
                            case UNKNOWN, READ_ONLY, UPDATE -> firstContractKey;
                            // We might be removing the head slot from the existing list
                            case REMOVAL ->
                                removeAccessedValue(
                                        store,
                                        firstContractKey,
                                        contractAccesses.contractID(),
                                        tuweniToPbjBytes(access.key()));
                            case ZERO_INTO_EMPTY_SLOT -> {
                                // Ensure a "new" zero isn't put into state, remove from KV state
                                store.removeSlot(
                                        new SlotKey(contractAccesses.contractID(), tuweniToPbjBytes(access.key())));
                                yield firstContractKey;
                            }
                            // We always insert the new slot at the head
                            case INSERTION ->
                                insertAccessedValue(
                                        store,
                                        firstContractKey,
                                        tuweniToPbjBytes(requireNonNull(access.writtenValue())),
                                        contractAccesses.contractID(),
                                        tuweniToPbjBytes(access.key()));
                        };
                firstKeys.put(contractAccesses.contractID(), newFirstContractKey);
            }
        }));

        // Update contract metadata with the net change in slots used
        long slotUsageChange = 0;
        for (final var change : allSizeChanges) {
            if (change.contractID().equals(HTS_HOOKS_CONTRACT_ID)) {
                // Skip managing slot count for 0x16d, as its storage is managed separately
                continue;
            }
            if (change.numInsertions() != 0 || change.numRemovals() != 0) {
                enhancement
                        .operations()
                        .updateStorageMetadata(
                                change.contractID(),
                                firstKeys.getOrDefault(change.contractID(), Bytes.EMPTY),
                                change.netChange());
                slotUsageChange += change.netChange();
            }
        }
        if (slotUsageChange != 0) {
            store.adjustSlotCount(slotUsageChange);
        }
    }

    /**
     * @param enhancement the enhancement for the current transaction
     * Returns the first storage key for the contract or Bytes.Empty if none exists. df
     *
     * @param enhancement the enhancement for the current transaction
     * @param contractID the contract id
     * @return the first storage key for the contract or null if none exists.
     */
    @NonNull
    private Bytes contractFirstKeyOf(@NonNull final Enhancement enhancement, @NonNull final ContractID contractID) {
        final var account = enhancement.nativeOperations().getAccount(contractID);
        return account != null ? account.firstContractStorageKey() : Bytes.EMPTY;
    }

    /**
     * Removes the given key from the slot storage and from the linked list of storage for the given contract, and removes the
     *
     * @param store Contract storage store
     * @param firstContractKey The first key in the linked list of storage for the given contract
     * @param contractID The contract id under consideration
     * @param key The slot key to remove
     * @return the new first key in the linked list of storage for the given contract
     */
    @NonNull
    private Bytes removeAccessedValue(
            @NonNull final ContractStateStore store,
            @NonNull Bytes firstContractKey,
            @NonNull final ContractID contractID,
            @NonNull final Bytes key) {
        requireNonNull(firstContractKey);
        requireNonNull(contractID);
        requireNonNull(store);
        requireNonNull(key);
        final var slotKey = new SlotKey(contractID, key);
        try {
            final var slotValue = slotValueFor(store, slotKey, "Missing key ");
            final var nextKey = slotValue.nextKey();
            final var prevKey = slotValue.previousKey();
            if (!Bytes.EMPTY.equals(nextKey)) {
                updatePrevFor(new SlotKey(contractID, nextKey), prevKey, store);
            }
            if (!Bytes.EMPTY.equals(prevKey)) {
                updateNextFor(new SlotKey(contractID, prevKey), nextKey, store);
            }
            firstContractKey = key.equals(firstContractKey) ? nextKey : firstContractKey;
        } catch (Exception irreparable) {
            // Since maintaining linked lists is not mission-critical, just log the error and continue
            log.error(
                    "Failed link management when removing {}; will be unable to" + " expire all slots for contract {}",
                    key,
                    contractID,
                    irreparable);
        }
        store.removeSlot(slotKey);
        return firstContractKey;
    }

    /**
     * Inserts the given key into the slot storage and into the linked list of storage for the given contract.
     *
     * @param store Contract storage store
     * @param firstContractKey The first key in the linked list of storage for the given contract
     * @param newValue The new value for the slot
     * @param contractID The contract id under consideration
     * @param newKey The slot key to insert
     * @return the new first key in the linked list of storage for the given contract
     */
    @NonNull
    private Bytes insertAccessedValue(
            @NonNull final ContractStateStore store,
            @NonNull final Bytes firstContractKey,
            @NonNull final Bytes newValue,
            @NonNull final ContractID contractID,
            @NonNull final Bytes newKey) {
        requireNonNull(store);
        requireNonNull(newKey);
        requireNonNull(newValue);
        try {
            if (!Bytes.EMPTY.equals(firstContractKey)) {
                updatePrevFor(new SlotKey(contractID, firstContractKey), newKey, store);
            }
        } catch (Exception irreparable) {
            // Since maintaining linked lists is not mission-critical, just log the error and continue
            log.error(
                    "Failed link management when inserting {}; will be unable to" + " expire all slots for contract {}",
                    newKey,
                    contractID,
                    irreparable);
        }
        store.putSlot(new SlotKey(contractID, newKey), new SlotValue(newValue, Bytes.EMPTY, firstContractKey));
        return newKey;
    }

    private void updatePrevFor(
            @NonNull final SlotKey key, @NonNull final Bytes newPrevKey, @NonNull final ContractStateStore store) {
        final var value = slotValueFor(store, key, "Missing next key ");
        store.putSlot(key, value.copyBuilder().previousKey(newPrevKey).build());
    }

    private void updateNextFor(
            @NonNull final SlotKey key, @NonNull final Bytes newNextKey, @NonNull final ContractStateStore store) {
        final var value = slotValueFor(store, key, "Missing prev key ");
        store.putSlot(key, value.copyBuilder().nextKey(newNextKey).build());
    }

    @NonNull
    private SlotValue slotValueFor(
            @NonNull final ContractStateStore store, @NonNull final SlotKey slotKey, @NonNull final String msgOnError) {
        return requireNonNull(store.getSlotValue(slotKey), () -> msgOnError + slotKey.key());
    }
}
