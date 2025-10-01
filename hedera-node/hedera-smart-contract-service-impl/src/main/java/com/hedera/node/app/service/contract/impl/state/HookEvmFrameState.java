// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_16D_CONTRACT_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HookId;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * EVM frame state used during hook execution. For address 0x16d, it returns
 * the executing hook's contract bytecode (fetched from the hook store).
 *
 * (Additional behavior like storage redirection and debit-from-owner will be
 * added here in follow-ups.)
 */
public class HookEvmFrameState extends DispatchingEvmFrameState {
    private final WritableEvmHookStore evmHookStore;
    private final HookId hookId;

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
    }

    @Override
    @NonNull
    public Bytes getCode(@NonNull ContractID contractID) {
        requireNonNull(contractID);
        if (isHookContract(contractID)) {
            final var hookState = evmHookStore.getEvmHook(hookId);
            final var hookContract = requireNonNull(hookState).hookContractIdOrThrow();
            return super.getCode(hookContract);
        }
        return super.getCode(contractID);
    }

    private boolean isHookContract(@NonNull final ContractID contractID) {
        return HTS_HOOKS_16D_CONTRACT_ID.equals(contractID);
    }
}
