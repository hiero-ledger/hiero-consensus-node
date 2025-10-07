// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state.hooks;

import static com.hedera.hapi.util.HapiUtils.CONTRACT_ID_COMPARATOR;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore.ZERO_KEY;
import static com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore.minimalKey;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniUInt256;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.service.token.HookDispatchUtils.HTS_HOOKS_CONTRACT_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.DispatchingEvmFrameState;
import com.hedera.node.app.service.contract.impl.state.StorageAccess;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.node.app.service.contract.impl.state.TxStorageUsage;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * EVM frame state used during hook execution. For address 0x16d, it returns
 * the executing hook's contract bytecode (fetched from the hook store).
 */
public class HookEvmFrameState extends DispatchingEvmFrameState {
    private final EvmHookState hook;
    private final CodeFactory codeFactory;
    private final WritableEvmHookStore writableEvmHookStore;

    /**
     * @param nativeOperations the Hedera native operation
     * @param contractStateStore the contract store that manages the key/value states
     */
    public HookEvmFrameState(
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final ContractStateStore contractStateStore,
            @NonNull final WritableEvmHookStore writableEvmHookStore,
            @NonNull final CodeFactory codeFactory,
            @NonNull final EvmHookState hook) {
        super(nativeOperations, contractStateStore, codeFactory);
        this.hook = requireNonNull(hook);
        this.codeFactory = requireNonNull(codeFactory);
        this.writableEvmHookStore = requireNonNull(writableEvmHookStore);
    }

    /**
     * When accessing account while executing hook, return a proxy account that
     * fetches the bytecode from the hook store using the hookId.
     * Otherwise, delegate to the parent class to handle.
     */
    @Override
    public @Nullable MutableAccount getMutableAccount(@NonNull final Address address) {
        if (address.equals(HTS_HOOKS_CONTRACT_ADDRESS)) {
            return new ProxyEvmHook(this, hook, codeFactory);
        }
        return super.getMutableAccount(address);
    }

    @Override
    public @Nullable Address getAddress(final long number) {
        if (number == HTS_HOOKS_CONTRACT_ID.contractNumOrThrow()) {
            return HTS_HOOKS_CONTRACT_ADDRESS;
        }
        return super.getAddress(number);
    }

    @Override
    public @NonNull UInt256 getStorageValue(final ContractID contractID, @NonNull final UInt256 key) {
        if (HTS_HOOKS_CONTRACT_ID.equals(contractID)) {
            final var slotKey = minimalKey(hook.hookIdOrThrow(), Bytes.wrap(key.toArrayUnsafe()));
            final var value = writableEvmHookStore.getSlotValue(slotKey);
            if (value == null) {
                return UInt256.ZERO;
            }
            return UInt256.fromBytes(pbjToTuweniBytes(value.value()));
        }
        return super.getStorageValue(contractID, key);
    }

    @Override
    public @NonNull UInt256 getOriginalStorageValue(final ContractID contractID, @NonNull final UInt256 key) {
        if (HTS_HOOKS_CONTRACT_ID.equals(contractID)) {
            final var slotKey = minimalKey(hook.hookIdOrThrow(), Bytes.wrap(key.toArrayUnsafe()));
            final var value = writableEvmHookStore.getOriginalSlotValue(slotKey);
            if (value == null) {
                return UInt256.ZERO;
            }
            return UInt256.fromBytes(pbjToTuweniBytes(value.value()));
        }
        return super.getOriginalStorageValue(contractID, key);
    }

    @Override
    public void setStorageValue(
            @NonNull final ContractID contractID, @NonNull final UInt256 key, @NonNull final UInt256 value) {
        if (HTS_HOOKS_CONTRACT_ID.equals(contractID)) {
            final var slotKey = minimalKey(hook.hookIdOrThrow(), Bytes.wrap(key.toArrayUnsafe()));
            final var oldSlotValue = writableEvmHookStore.getSlotValue(slotKey);
            final var slotValue = new SlotValue(
                    tuweniToPbjBytes(requireNonNull(value)),
                    oldSlotValue == null ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY : oldSlotValue.previousKey(),
                    oldSlotValue == null ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY : oldSlotValue.nextKey());
            writableEvmHookStore.updateStorage(slotKey, slotValue);
            return;
        }
        super.setStorageValue(contractID, key, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull TxStorageUsage getTxStorageUsage(final boolean includeChangedKeys) {
        final Map<ContractID, List<StorageAccess>> modifications = new TreeMap<>(CONTRACT_ID_COMPARATOR);
        final Set<SlotKey> changedKeys = includeChangedKeys ? new HashSet<>() : null;
        writableEvmHookStore.getModifiedSlotKeys().forEach(slotKey -> {
            final var access = StorageAccess.newWrite(
                    slotKey.key().equals(ZERO_KEY) ? UInt256.ZERO : pbjToTuweniUInt256(slotKey.key()),
                    valueOrZero(writableEvmHookStore.getOriginalSlotValue(slotKey)),
                    valueOrZero(writableEvmHookStore.getSlotValue(slotKey)));
            modifications
                    .computeIfAbsent(HTS_HOOKS_CONTRACT_ID, k -> new ArrayList<>())
                    .add(access);
            if (includeChangedKeys && access.isLogicalChange()) {
                changedKeys.add(new SlotKey(HTS_HOOKS_CONTRACT_ID, slotKey.key()));
            }
        });
        final List<StorageAccesses> allChanges = new ArrayList<>();
        modifications.forEach(
                (number, storageAccesses) -> allChanges.add(new StorageAccesses(number, storageAccesses)));
        // get super class changes too
        final var contractStorageChanges = super.getTxStorageUsage(includeChangedKeys);
        if (contractStorageChanges.accesses() != null) {
            allChanges.addAll(contractStorageChanges.accesses());
        }
        if (includeChangedKeys && contractStorageChanges.changedKeys() != null) {
            changedKeys.addAll(contractStorageChanges.changedKeys());
        }

        return new TxStorageUsage(allChanges, changedKeys);
    }
}
