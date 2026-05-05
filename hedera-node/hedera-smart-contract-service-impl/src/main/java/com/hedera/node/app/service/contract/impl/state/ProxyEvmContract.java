// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.Bytecode;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;

/**
 * A concrete subclass of {@link AbstractProxyEvmAccount} that represents a contract account.
 * <p>
 * Responsible for retrieving the contract byte code from the {@link EvmFrameState}
 */
public class ProxyEvmContract extends AbstractProxyEvmAccount {

    public ProxyEvmContract(AccountID accountID, DispatchingEvmFrameState state) {
        super(accountID, state);
    }

    @Override
    public @NonNull Bytes getCode() {
        return state.getCode(hederaContractId());
    }

    @Override
    public @NonNull Hash getCodeHash() {
        return state.getCodeHash(hederaContractId());
    }

    @Override
    public int getCodeSize() {
        // TODO(Pectra): is this override still needed?
        ContractID cid = hederaContractId();
        Bytecode code = state.contractStateStore.getBytecode(cid);
        // While the length() call returns a long type, the underlying
        // implementation (being a Java array and all) only returns an int.
        return code == null ? 0 : (int) code.code().length();
    }
}
