// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state.hooks;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.token.HookDispatchUtils.HTS_HOOKS_CONTRACT_NUM;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.node.app.service.contract.impl.state.AbstractProxyEvmAccount;
import com.hedera.node.app.service.contract.impl.state.EvmFrameState;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * Represents a hook owned by an account (which might be a contract account).
 * <p>
 * This hook "blends" three pieces:
 * <ol>
 *      <li>The bytecode of a deployed <b>contract</b>.</li>
 *      <li>The account state of the hook's owner <b>account</b>.</li>
 *      <li>The storage of the <b>hook itself</b>.</li>
 * </ol>
 */
public class ProxyEvmAccountHook extends AbstractProxyEvmAccount {
    private final EvmHookState hookState;
    private final CodeFactory codeFactory;
    private final ContractID hederaContractId;

    public ProxyEvmAccountHook(
            @NonNull final EvmFrameState state,
            @NonNull final EvmHookState hookState,
            @NonNull final CodeFactory codeFactory,
            @NonNull final EntityIdFactory entityIdFactory) {
        // Internally both account and contract hooks use account id for owner entity id
        super(hookState.hookIdOrThrow().entityIdOrThrow().accountIdOrThrow(), state);
        this.hookState = requireNonNull(hookState);
        this.codeFactory = requireNonNull(codeFactory);
        this.hederaContractId = requireNonNull(entityIdFactory).newContractId(HTS_HOOKS_CONTRACT_NUM);
    }

    @Override
    public @NonNull Code getEvmCode(@NonNull final Bytes functionSelector, @NonNull final CodeFactory codeFactory) {
        requireNonNull(functionSelector);
        requireNonNull(codeFactory);
        return codeFactory.createCode(getCode(), false);
    }

    @Override
    public Address getAddress() {
        return HTS_HOOKS_CONTRACT_ADDRESS;
    }

    @Override
    @NonNull
    public ContractID hederaContractId() {
        return hederaContractId;
    }

    @Override
    public @NonNull Bytes getCode() {
        return state.getCode(hookState.hookContractIdOrThrow());
    }

    @Override
    public @NonNull Hash getCodeHash() {
        return state.getCodeHash(hookState.hookContractIdOrThrow(), codeFactory);
    }

    @Override
    public @NonNull UInt256 getStorageValue(@NonNull final UInt256 key) {
        return state.getStorageValue(hederaContractId, key);
    }

    @Override
    public boolean isRegularAccount() {
        return false;
    }
}
