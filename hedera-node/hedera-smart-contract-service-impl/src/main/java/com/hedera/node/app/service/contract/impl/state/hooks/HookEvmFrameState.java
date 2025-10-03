// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state.hooks;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_16D_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_16D_CONTRACT_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.DispatchingEvmFrameState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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

    /**
     * @param nativeOperations the Hedera native operation
     * @param contractStateStore the contract store that manages the key/value states
     */
    public HookEvmFrameState(
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final ContractStateStore contractStateStore,
            @NonNull final CodeFactory codeFactory,
            @NonNull final EvmHookState hook) {
        super(nativeOperations, contractStateStore, codeFactory);
        this.hook = requireNonNull(hook);
        this.codeFactory = requireNonNull(codeFactory);
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
}
