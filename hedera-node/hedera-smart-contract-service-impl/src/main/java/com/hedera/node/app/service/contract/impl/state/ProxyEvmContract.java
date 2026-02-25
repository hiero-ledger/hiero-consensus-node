// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * A concrete subclass of {@link AbstractProxyEvmAccount} that represents a contract account.
 * <p>
 * Responsible for retrieving the contract byte code from the {@link EvmFrameState}
 */
public class ProxyEvmContract extends AbstractProxyEvmAccount {

    private final CodeFactory codeFactory;

    public ProxyEvmContract(
            final AccountID accountID, @NonNull final EvmFrameState state, @NonNull final CodeFactory codeFactory) {
        super(accountID, state);
        this.codeFactory = codeFactory;
    }

    @Override
    public @NonNull Code getEvmCode(@NonNull final Bytes functionSelector, @NonNull final CodeFactory codeFactory) {
        return codeFactory.createCode(getCode(), false);
    }

    @Override
    public @NonNull Bytes getCode() {
        return state.getCode(hederaContractId());
    }

    @Override
    public @NonNull Hash getCodeHash() {
        return state.getCodeHash(hederaContractId(), codeFactory);
    }
}
