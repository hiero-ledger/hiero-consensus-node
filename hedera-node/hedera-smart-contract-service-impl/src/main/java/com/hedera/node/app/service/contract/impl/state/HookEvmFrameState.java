// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_16D_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_16D_CONTRACT_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HookId;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * EVM frame state used during hook execution. For address 0x16d, it returns
 * the executing hook's contract bytecode (fetched from the hook store).
 * <p>
 * (Additional behavior like storage redirection and debit-from-owner will be
 * added here in follow-ups.)
 */
public class HookEvmFrameState extends DispatchingEvmFrameState {
    private final WritableEvmHookStore evmHookStore;
    private final HookId hookId;
    private final CodeFactory codeFactory;

    /**
     * @param nativeOperations the Hedera native operation
     * @param contractStateStore the contract store that manages the key/value states
     */
    public HookEvmFrameState(
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final ContractStateStore contractStateStore,
            @NonNull final CodeFactory codeFactory,
            @NonNull final WritableEvmHookStore evmHookStore,
            @NonNull final HookId hookId) {
        super(nativeOperations, contractStateStore, codeFactory);
        this.evmHookStore = evmHookStore;
        this.hookId = hookId;
        this.codeFactory = requireNonNull(codeFactory);
    }

    @Override
    protected @Nullable MutableAccount getAccountInternal(final AccountID accountID, final Address address) {
        if (isHookContract(address)) {
            final var evmHookState = requireNonNull(evmHookStore.getEvmHook(hookId));
            return new ProxyEvmHook(this, evmHookState, codeFactory);
        }
        return super.getAccountInternal(accountID, address);
    }

    @Override
    protected @Nullable Address getAddressInternal(final long number) {
        if (number == HTS_HOOKS_16D_CONTRACT_ID.contractNumOrThrow()) {
            return HTS_HOOKS_16D_CONTRACT_ADDRESS;
        }
        return super.getAddressInternal(number);
    }

    public static boolean isHookContract(@NonNull final ContractID contractID) {
        return HTS_HOOKS_16D_CONTRACT_ID.equals(contractID);
    }

    public static boolean isHookContract(@NonNull final Address address) {
        return address.equals(HTS_HOOKS_16D_CONTRACT_ADDRESS);
    }
}
