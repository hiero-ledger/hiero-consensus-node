// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * A concrete subclass of {@link AbstractProxyEvmAccount} that represents a contract account.
 * Responsible for retrieving the contract byte code from the {@link EvmFrameState}.
 */
public class ProxyEvmContract extends AbstractProxyEvmAccount {
    private static final Logger LOG = LogManager.getLogger(FrameBuilder.class);

    private final CodeFactory codeFactory;

    public ProxyEvmContract(
            final AccountID accountID, @NonNull final EvmFrameState state, @NonNull final CodeFactory codeFactory) {
        super(accountID, state);
        this.codeFactory = codeFactory;
    }

    @Override
    public @NonNull Bytes getCode() {
        LOG.warn("ProxyEvmContract.getCode (from storage))");
        return state.getCode(hederaContractId());
    }

    @Override
    public @NonNull Hash getCodeHash() {
        return state.getCodeHash(hederaContractId(), codeFactory);
    }
}
