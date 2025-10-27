// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state.hooks;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.token.HookDispatchUtils.HTS_HOOKS_CONTRACT_NUM;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HookId;
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
 * A concrete subclass of {@link AbstractProxyEvmAccount} that represents a hook.
 * <p>
 * Responsible for retrieving the contract byte code from hook's contract. Always returns the address of the
 * Allowance hook address (0x16d). Otherwise, use all the information from the owner of the hook.
 * A hook "blends" three pieces:
 * <ol>
 *      <li>The bytecode of a deployed <b>contract</b>.</li>
 *      <li>The account state of the hook's owner <b>account</b>.</li>
 *      <li>The storage of the <b>hook itself</b>.</li>
 * </ol>
 *  Only (1) and (2) are implemented now. Future PRs will fully integrate (3).
 */
public class ProxyEvmHook extends AbstractProxyEvmAccount {
    private final EvmHookState hookState;
    private final CodeFactory codeFactory;
    private final EntityIdFactory entityIdFactory;

    public ProxyEvmHook(
            @NonNull final EvmFrameState state,
            @NonNull final EvmHookState hookState,
            final CodeFactory codeFactory,
            @NonNull final EntityIdFactory entityIdFactory) {
        super(getOwnerId(hookState.hookIdOrThrow()), state);
        this.hookState = requireNonNull(hookState);
        this.codeFactory = codeFactory;
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    @Override
    public @NonNull Code getEvmCode(@NonNull final Bytes functionSelector, @NonNull final CodeFactory codeFactory) {
        return codeFactory.createCode(getCode(), false);
    }

    @Override
    public Address getAddress() {
        return HTS_HOOKS_CONTRACT_ADDRESS;
    }

    @Override
    @NonNull
    public ContractID hederaContractId() {
        return entityIdFactory.newContractId(HTS_HOOKS_CONTRACT_NUM);
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
        return state.getStorageValue(entityIdFactory.newContractId(HTS_HOOKS_CONTRACT_NUM), key);
    }

    @NonNull
    private static AccountID getOwnerId(final @NonNull HookId hookId) {
        return requireNonNull(hookId).entityIdOrThrow().hasAccountId()
                ? hookId.entityIdOrThrow().accountIdOrThrow()
                : asAccountId(hookId.entityIdOrThrow().contractIdOrThrow());
    }

    @Override
    public boolean isRegularAccount() {
        return false;
    }

    private static AccountID asAccountId(final ContractID contractID) {
        return AccountID.newBuilder()
                .shardNum(contractID.shardNum())
                .realmNum(contractID.realmNum())
                .accountNum(contractID.contractNumOrThrow())
                .build();
    }
}
