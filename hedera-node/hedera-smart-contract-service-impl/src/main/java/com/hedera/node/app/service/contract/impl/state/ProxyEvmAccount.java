// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * A concrete subclass of {@link AbstractProxyEvmAccount} that represents a regular account.
 * Responsible for retrieving the code (either EIP-7702 delegation indicator or empty)
 * from the {@link EvmFrameState}.
 */
public class ProxyEvmAccount extends AbstractProxyEvmAccount {
    private final CodeFactory codeFactory;

    public ProxyEvmAccount(
            final AccountID accountID, @NonNull final EvmFrameState state, @NonNull final CodeFactory codeFactory) {
        super(accountID, state);
        this.codeFactory = codeFactory;
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
