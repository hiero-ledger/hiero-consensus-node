// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static org.hyperledger.besu.crypto.Hash.keccak256;
import static org.hyperledger.besu.evm.worldstate.CodeDelegationHelper.CODE_DELEGATION_PREFIX;

import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.code.CodeV0;

/**
 * A concrete subclass of {@link AbstractProxyEvmAccount} that represents a regular account.
 * Responsible for retrieving the delegation address from the Account entity
 * and returning the appropriate code - either EIP-7702 delegation indicator or empty.
 */
public class ProxyEvmAccount extends AbstractProxyEvmAccount {
    private static final Logger LOG = LogManager.getLogger(FrameBuilder.class);

    private final Account account;

    public ProxyEvmAccount(final Account account, @NonNull final EvmFrameState state) {
        super(account.accountId(), state);

        this.account = account;
    }

    @Override
    public @NonNull Bytes getCode() {
        LOG.warn("ProxyEvmAccount.getCode of {} delegation addr {} ", account.accountId(), account.delegationAddress());
        if (account.delegationAddress().length() == 0) {
            LOG.warn("ProxyEvmAccount.getCode return empty");
            return Bytes.EMPTY;
        } else {
            LOG.warn("ProxyEvmAccount.getCode return delegation indicator");
            return createDelegationIndicator(pbjToTuweniBytes(account.delegationAddress()));
        }
    }

    private static Bytes createDelegationIndicator(Bytes delegationAddress) {
        return Bytes.concatenate(CODE_DELEGATION_PREFIX, delegationAddress);
    }

    @Override
    public @NonNull Hash getCodeHash() {
        if (account.delegationAddress().length() == 0) {
            return CodeV0.EMPTY_CODE.getCodeHash();
        } else {
            return Hash.wrap(keccak256(getCode()));
        }
    }
}
