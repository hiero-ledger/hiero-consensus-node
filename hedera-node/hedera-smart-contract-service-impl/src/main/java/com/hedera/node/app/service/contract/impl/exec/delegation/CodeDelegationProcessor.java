// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.delegation;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static org.hyperledger.besu.evm.account.Account.MAX_NONCE;
import static org.hyperledger.besu.evm.worldstate.CodeDelegationHelper.CODE_DELEGATION_PREFIX;

import com.hedera.node.app.hapi.utils.ethereum.CodeDelegation;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import java.math.BigInteger;
import java.util.List;
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
     * @param lazyCreationGasAvailable gas amount available for charing Hedera-specific lazy account creation cost
     * @param codeDelegations code delegations to process
     * @return The result of the code delegation processing.
     */
    public CodeDelegationResult process(
            final WorldUpdater worldUpdater,
            final long lazyCreationGasAvailable,
            List<CodeDelegation> codeDelegations) {
        final CodeDelegationResult result = new CodeDelegationResult(lazyCreationGasAvailable);

        if (codeDelegations == null || codeDelegations.isEmpty()) {
            return result;
        }

        // Get a new proxy world updater to handle state changes and records for code delegations.
        final ProxyWorldUpdater proxyWorldUpdater = (ProxyWorldUpdater) worldUpdater.updater();
        codeDelegations.forEach(codeDelegation -> processCodeDelegation(proxyWorldUpdater, codeDelegation, result));
        // Commit the changes for code delegations.
        proxyWorldUpdater.commit();

        return result;
    }

    private void processCodeDelegation(
            final ProxyWorldUpdater proxyWorldUpdater,
            final CodeDelegation codeDelegation,
            final CodeDelegationResult result) {
        LOG.trace("Processing code delegation: {}", codeDelegation);

        if ((codeDelegation.getChainId() != 0) && (chainId != codeDelegation.getChainId())) {
            result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.ChainIdMismatch);
            return;
        }

        if (codeDelegation.nonce() == MAX_NONCE) {
            result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.NonceMismatch);
            return;
        }

        if (codeDelegation.getS().compareTo(HALF_CURVE_ORDER) > 0) {
            result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.Other);
            return;
        }

        if (codeDelegation.getYParity() >= MAX_Y_PARITY) {
            result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.Other);
            return;
        }

        final Optional<EthTxSigs> authorizer = EthTxSigs.extractAuthoritySignature(codeDelegation);
        if (authorizer.isEmpty()) {
            result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.Other);
            return;
        }

        final var authorizerAddress = Address.wrap(Bytes.wrap(authorizer.get().address()));
        final Optional<MutableAccount> maybeAuthorityAccount =
                Optional.ofNullable(proxyWorldUpdater.getAccount(authorizerAddress));

        final var delegatedContractAddress =
                Address.fromHexString(Bytes.wrap(codeDelegation.address()).toString());

        MutableAccount authority;
        if (maybeAuthorityAccount.isEmpty()) {
            // only create an account if nonce is valid
            if (codeDelegation.nonce() != 0) {
                result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.NonceMismatch);
                return;
            }

            // The base gas defined in EIP-7702 (PER_EMPTY_ACCOUNT_COST = 25000) is already included
            // in the transaction intrinsic gas.
            // Here we're only charging the Hedera-specific lazy account creation cost.
            final var lazyCreationCost = proxyWorldUpdater.lazyCreationCostInGas(authorizerAddress);
            if (result.remainingLazyCreationGasAvailable() >= lazyCreationCost) {
                result.addHollowAccountCreationGasCharge(lazyCreationCost);
            } else {
                // Create failed record due to insufficient gas for lazy creation
                final var failedRecord =
                        proxyWorldUpdater.createNewChildRecordBuilder(CryptoCreateStreamBuilder.class, CRYPTO_CREATE);
                failedRecord.status(INSUFFICIENT_GAS);
                result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.InsufficientGasForLazyCreation);
                return;
            }

            if (!proxyWorldUpdater.createAccountWithCodeDelegation(authorizerAddress, delegatedContractAddress)) {
                result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.Other);
                return;
            }

            authority = proxyWorldUpdater.getAccount(authorizerAddress);
            if (authority == null) {
                result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.Other);
                return;
            }
        } else {
            authority = maybeAuthorityAccount.get();

            if (!canSetCodeDelegation(authority)) {
                result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.AccountAlreadyHasCode);
                return;
            }

            if (codeDelegation.nonce() != authority.getNonce()) {
                result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.NonceMismatch);
                return;
            }

            // Ensure that the account is a regular account
            if (!((HederaEvmAccount) authority).isRegularAccount()) {
                result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.Other);
                return;
            }

            if (!proxyWorldUpdater.setAccountCodeDelegation(
                    ((HederaEvmAccount) authority).hederaId(), delegatedContractAddress)) {
                result.reportIgnoredEntry(CodeDelegationResult.EntryIgnoreReason.Other);
                return;
            }
            result.incAuthorizationsEligibleForRefund();
        }

        result.incSuccessfullyProcessedAuthorizations();
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
