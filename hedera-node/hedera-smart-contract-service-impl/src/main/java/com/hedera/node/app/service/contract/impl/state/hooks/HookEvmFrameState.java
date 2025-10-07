// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state.hooks;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_16D_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_16D_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore.minimalKey;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.node.app.service.contract.ReadableEvmHookStore;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.DispatchingEvmFrameState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * EVM frame state used during hook execution. For address 0x16d, it returns
 * the executing hook's contract bytecode (fetched from the hook store).
 * TODO: Additional behavior like storage redirection and debit-from-owner will be added here in next PRs
 */
public class HookEvmFrameState extends DispatchingEvmFrameState {
    private final EvmHookState hook;
    private final CodeFactory codeFactory;
    private final ReadableEvmHookStore readableEvmHookStore;

    /**
     * @param nativeOperations the Hedera native operation
     * @param contractStateStore the contract store that manages the key/value states
     */
    public HookEvmFrameState(
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final ContractStateStore contractStateStore,
            @NonNull final ReadableEvmHookStore readableEvmHookStore,
            @NonNull final CodeFactory codeFactory,
            @NonNull final EvmHookState hook) {
        super(nativeOperations, contractStateStore, codeFactory);
        this.hook = requireNonNull(hook);
        this.codeFactory = requireNonNull(codeFactory);
        this.readableEvmHookStore = requireNonNull(readableEvmHookStore);
    }

    /**
     * When accessing account while executing hook, return a proxy account that
     * fetches the bytecode from the hook store using the hookId.
     * Otherwise, delegate to the parent class to handle.
     */
    @Override
    public @Nullable MutableAccount getMutableAccount(@NonNull final Address address) {
        if (address.equals(HTS_HOOKS_16D_CONTRACT_ADDRESS)) {
            return new ProxyEvmHook(this, hook, codeFactory);
        }
        return super.getMutableAccount(address);
    }

    @Override
    public @Nullable Address getAddress(final long number) {
        if (number == HTS_HOOKS_16D_CONTRACT_ID.contractNumOrThrow()) {
            return HTS_HOOKS_16D_CONTRACT_ADDRESS;
        }
        return super.getAddress(number);
    }

    @Override
    public @NonNull UInt256 getStorageValue(final ContractID contractID, @NonNull final UInt256 key) {
        if (HTS_HOOKS_16D_CONTRACT_ID.equals(contractID)) {
            final var slotKey = minimalKey(hook.hookId(), Bytes.wrap(key.toArrayUnsafe()));
            final var value = readableEvmHookStore.getSlotValue(slotKey);
            if (value == null) {
                return UInt256.ZERO;
            }
            return UInt256.fromBytes(pbjToTuweniBytes(value.value()));
        }
        return super.getStorageValue(contractID, key);
    }
}
