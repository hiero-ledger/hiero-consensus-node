// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static org.hyperledger.besu.evm.worldstate.CodeDelegationHelper.CODE_DELEGATION_PREFIX;

import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
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

    /// A cache for the code. No synchronization for performance reasons.
    private Bytes $code;
    /// A cache for the codePBJ. No synchronization for performance reasons.
    private com.hedera.pbj.runtime.io.buffer.Bytes $codePBJ;

    public ProxyEvmAccount(final Account account, @NonNull final DispatchingEvmFrameState state) {
        super(account.accountId(), state);

        this.account = account;
    }

    /// {@inheritDoc}
    /// Returns an eventually cached Bytes with the code prefixed by the CODE_DELEGATION_PREFIX.
    /// Assumes that the caller does NOT modify the underlying bytes, even if they use toArrayUnsafe().
    @Override
    public @NonNull Bytes getCode() {
        if ($code == null) {
            if (account.delegationAddress().length() == 0) {
                $code = Bytes.EMPTY;
            } else {
                $code = Bytes.wrap(getCodeByteArray(account.delegationAddress()));
            }
        }
        return $code;
    }

    @Override
    public com.hedera.pbj.runtime.io.buffer.Bytes getCodePBJ() {
        if ($codePBJ == null) {
            // NOTE: it would be way more efficient as:
            // `$codePBJ = com.hedera.pbj.runtime.io.buffer.Bytes.wrap(getCode().toArrayUnsafe())`
            // However, this getCodePBJ() prepends the prefix unconditionally,
            // unlike the getCode() above. So we must recompute it the second time:
            $codePBJ = createDelegationIndicatorPJB(account.delegationAddress());
        }
        return $codePBJ;
    }

    public static com.hedera.pbj.runtime.io.buffer.Bytes createDelegationIndicatorPJB(
            com.hedera.pbj.runtime.io.buffer.Bytes delegationAddress) {
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(getCodeByteArray(delegationAddress));
    }

    /// An efficient builder for a code byte[] that returns an unsafe array (because it's mutable).
    /// Assumes delegationAddress isn't empty, but shouldn't fail with an empty one.
    /// Up to the caller to decide if an empty delegationAddress should be treated differently.
    private static byte[] getCodeByteArray(final com.hedera.pbj.runtime.io.buffer.Bytes delegationAddress) {
        final byte[] code = new byte[Math.toIntExact(delegationAddress.length()) + CODE_DELEGATION_PREFIX.size()];
        System.arraycopy(CODE_DELEGATION_PREFIX.toArrayUnsafe(), 0, code, 0, CODE_DELEGATION_PREFIX.size());
        // The below call performs an efficient System.arraycopy() as well:
        delegationAddress.writeTo(code, CODE_DELEGATION_PREFIX.size());
        return code;
    }

    @Override
    public @NonNull Hash getCodeHash() {
        if (account.delegationAddress().length() == 0) {
            return Code.EMPTY_CODE.getCodeHash();
        } else {
            return Hash.wrap(
                    Bytes32.wrap(MiscCryptoUtils.keccak256DigestOf(getCode().toArrayUnsafe())));
        }
    }
}
