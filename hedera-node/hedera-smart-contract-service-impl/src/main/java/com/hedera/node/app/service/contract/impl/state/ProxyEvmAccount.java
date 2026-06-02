// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static org.hyperledger.besu.crypto.Hash.keccak256;
import static org.hyperledger.besu.evm.worldstate.CodeDelegationHelper.CODE_DELEGATION_PREFIX;

import com.hedera.hapi.node.state.token.Account;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.Code;

/**
 * A concrete subclass of {@link AbstractProxyEvmAccount} that represents a regular account.
 * Responsible for retrieving the delegation address from the Account entity
 * and returning the appropriate code - either EIP-7702 delegation indicator or empty.
 */
public class ProxyEvmAccount extends AbstractProxyEvmAccount {

    private static final com.hedera.pbj.runtime.io.buffer.Bytes CODE_DELEGATION_PREFIX_PJB =
            com.hedera.pbj.runtime.io.buffer.Bytes.wrap(CODE_DELEGATION_PREFIX.toArray());

    private final Account account;

    public ProxyEvmAccount(final Account account, @NonNull final DispatchingEvmFrameState state) {
        super(account.accountId(), state);

        this.account = account;
    }

    @Override
    public @NonNull Bytes getCode() {
        if (account.delegationAddress().length() == 0) {
            return Bytes.EMPTY;
        } else {
            return createDelegationIndicator(pbjToTuweniBytes(account.delegationAddress()));
        }
    }

    private static Bytes createDelegationIndicator(Bytes delegationAddress) {
        return Bytes.concatenate(CODE_DELEGATION_PREFIX, delegationAddress);
    }

    @Override
    public com.hedera.pbj.runtime.io.buffer.Bytes getCodePBJ() {
        return createDelegationIndicatorPJB(account.delegationAddress());
    }

    public static com.hedera.pbj.runtime.io.buffer.Bytes createDelegationIndicatorPJB(
            com.hedera.pbj.runtime.io.buffer.Bytes delegationAddress) {
        return com.hedera.pbj.runtime.io.buffer.Bytes.merge(CODE_DELEGATION_PREFIX_PJB, delegationAddress);
    }

    @Override
    public @NonNull Hash getCodeHash() {
        if (account.delegationAddress().length() == 0) {
            return Code.EMPTY_CODE.getCodeHash();
        } else {
            return Hash.wrap(keccak256(getCode()));
        }
    }
}
