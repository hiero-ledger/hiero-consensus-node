// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.delegation;

import static org.hyperledger.besu.evm.account.Account.MAX_NONCE;

import com.hedera.node.app.hapi.utils.ethereum.CodeDelegation;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import java.math.BigInteger;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processor for handling code delegations for EIP-7702 transactions.
 */
public record CodeDelegationProcessor(long chainId) {
    private static final Logger LOG = LoggerFactory.getLogger(CodeDelegationProcessor.class);

    // The half of the secp256k1 curve order, used to validate the signature.
    private static final BigInteger HALF_CURVE_ORDER =
            new BigInteger("7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0", 16);

    private static final int MAX_Y_PARITY = 2 ^ 8;

    public static final Bytes CODE_DELEGATION_PREFIX = Bytes.fromHexString("ef0100");

    /** The size of the delegated code */
    public static final int DELEGATED_CODE_SIZE = CODE_DELEGATION_PREFIX.size() + Address.SIZE;

    /**
     * At the start of executing the transaction, after incrementing the sender’s nonce, for each authorization we do
     * the following:
     *
     * <ol>
     *   <li>Verify the chain id is either 0 or the chain's current ID.
     *   <li>`authority = ecrecover(keccak(MAGIC || rlp([chain_id, address, nonce])), y_parity, r, s]`
     *   <li>Add `authority` to `accessed_addresses` (as defined in [EIP-2929](./eip-2929.md).)
     *   <li>Verify the code of `authority` is either empty or already delegated.
     *   <li>Verify the nonce of `authority` is equal to `nonce`.
     *   <li>Add `PER_EMPTY_ACCOUNT_COST - PER_AUTH_BASE_COST` gas to the global refund counter if
     *       `authority` exists in the trie.
     *   <li>Set the code of `authority` to be `0xef0100 || address`. This is a delegation
     *       designation.
     *   <li>Increase the nonce of `authority` by one.
     * </ol>
     *
     * @param worldUpdater The world state updater which is aware of code delegation.
     * @param transaction  The transaction being processed.
     * @return The result of the code delegation processing.
     */
    public CodeDelegationResult process(final WorldUpdater worldUpdater, final HederaEvmTransaction transaction) {
        final CodeDelegationResult result = new CodeDelegationResult();

        if (transaction.codeDelegations() == null) {
            // If there are no code delegations, we can return early.
            return result;
        }

        transaction
                .codeDelegations()
                .forEach(codeDelegation -> setAccountCodeToDelegationIndicator(worldUpdater, codeDelegation, result));

        return result;
    }

    private void setAccountCodeToDelegationIndicator(
            final WorldUpdater worldUpdater, final CodeDelegation codeDelegation, final CodeDelegationResult result) {
        LOG.trace("Processing code delegation: {}", codeDelegation);

        if ((codeDelegation.getChainId() != 0) && (chainId != codeDelegation.getChainId())) {
            return;
        }

        if (codeDelegation.nonce() == MAX_NONCE) {
            return;
        }

        if (codeDelegation.getS().compareTo(HALF_CURVE_ORDER) > 0) {
            return;
        }

        if (codeDelegation.getR().compareTo(HALF_CURVE_ORDER) > 0) {
            return;
        }

        if (codeDelegation.getYParity() >= MAX_Y_PARITY) {
            return;
        }

        final Optional<EthTxSigs> authorizer = EthTxSigs.extractAuthoritySignature(codeDelegation);
        if (authorizer.isEmpty()) {
            return;
        }

        final var authorizerAddress = Address.wrap(Bytes.wrap(authorizer.get().address()));
        final Optional<MutableAccount> maybeAuthorityAccount =
                Optional.ofNullable(worldUpdater.getAccount(authorizerAddress));

        result.addAccessedDelegatorAddress(authorizerAddress);

        final var delegatedContractAddress = Address.wrap(Bytes.wrap(codeDelegation.address()));

        MutableAccount authority;
        if (maybeAuthorityAccount.isEmpty()) {
            // only create an account if nonce is valid
            if (codeDelegation.nonce() != 0) {
                return;
            }

            if (!((ProxyWorldUpdater) worldUpdater)
                    .createAccountWithCodeDelegationIndicator(authorizerAddress, delegatedContractAddress)) {
                return;
            }

            authority = worldUpdater.getAccount(authorizerAddress);
            if (authority == null) {
                return;
            }
        } else {
            authority = maybeAuthorityAccount.get();

            if (!canSetCodeDelegation(authority)) {
                return;
            }

            if (codeDelegation.nonce() != authority.getNonce()) {
                return;
            }

            // Ensure that the account is a regular account
            if (!((HederaEvmAccount) authority).isRegularAccount()) {
                return;
            }

            if (!((ProxyWorldUpdater) worldUpdater)
                    .setAccountCodeDelegationIndicator(
                            ((HederaEvmAccount) authority).hederaId(), delegatedContractAddress)) {
                return;
            }
            result.incrementAlreadyExistingDelegators();
        }

        authority.incrementNonce();
    }

    private boolean canSetCodeDelegation(final Account account) {
        return account != null && (account.getCode().isEmpty() || hasCodeDelegation(account.getCode()));
    }

    private boolean hasCodeDelegation(final Bytes code) {
        return code != null
                && code.size() == DELEGATED_CODE_SIZE
                && code.slice(0, CODE_DELEGATION_PREFIX.size()).equals(CODE_DELEGATION_PREFIX);
    }
}
