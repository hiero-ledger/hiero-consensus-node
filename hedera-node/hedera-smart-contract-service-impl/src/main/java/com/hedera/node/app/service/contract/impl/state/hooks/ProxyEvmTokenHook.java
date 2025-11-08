// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state.hooks;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.token.HookDispatchUtils.HTS_HOOKS_CONTRACT_NUM;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.node.app.service.contract.impl.state.AbstractEvmEntityAccount;
import com.hedera.node.app.service.contract.impl.state.EvmFrameState;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * Represents a hook owned by a native HTS token.
 * <p>
 * This hook "blends" two pieces:
 * <ol>
 *      <li>The bytecode of a deployed <b>contract</b>.</li>
 *      <li>The storage of the <b>hook itself</b>.</li>
 * </ol>
 * Since an HTS token is not an account with a balance or nonce, these methods are not overridden.
 */
public class ProxyEvmTokenHook extends AbstractEvmEntityAccount {
    private final EvmHookState hookState;
    private final CodeFactory codeFactory;
    private final ContractID hederaContractId;

    public ProxyEvmTokenHook(
            @NonNull final EvmFrameState state,
            @NonNull final EvmHookState hookState,
            @NonNull final CodeFactory codeFactory,
            @NonNull final EntityIdFactory entityIdFactory) {
        super(HTS_HOOKS_CONTRACT_ADDRESS, state);
        this.hookState = requireNonNull(hookState);
        this.codeFactory = requireNonNull(codeFactory);
        this.hederaContractId = requireNonNull(entityIdFactory).newContractId(HTS_HOOKS_CONTRACT_NUM);
    }

    @Override
    public @NonNull ContractID hederaContractId() {
        return hederaContractId;
    }

    @Override
    public @NonNull Code getEvmCode(@NonNull final Bytes functionSelector, @NonNull final CodeFactory codeFactory) {
        requireNonNull(functionSelector);
        requireNonNull(codeFactory);
        return codeFactory.createCode(getCode(), false);
    }

    @Override
    public Bytes getCode() {
        return state.getCode(hookState.hookContractIdOrThrow());
    }

    @Override
    public Hash getCodeHash() {
        return state.getCodeHash(hookState.hookContractIdOrThrow(), codeFactory);
    }

    @Override
    public @NonNull UInt256 getStorageValue(@NonNull final UInt256 key) {
        requireNonNull(key);
        return state.getStorageValue(hederaContractId, key);
    }

    @Override
    public @NonNull UInt256 getOriginalStorageValue(@NonNull final UInt256 key) {
        requireNonNull(key);
        return state.getOriginalStorageValue(hederaContractId, key);
    }

    @Override
    public void setStorageValue(@NonNull final UInt256 key, @NonNull final UInt256 value) {
        requireNonNull(key);
        requireNonNull(value);
        state.setStorageValue(hederaContractId, key, value);
    }
}
