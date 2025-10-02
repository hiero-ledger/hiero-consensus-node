// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CANNOT_SET_HOOKS_AND_APPROVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOKS_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hedera.node.app.spi.validation.Validations.validateAccountID;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.math.BigInteger.ZERO;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.HooksConfig;
import com.hedera.node.config.data.LedgerConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A validator for the crypto transfer transaction.
 */
@Singleton
public class CryptoTransferValidator {
    private final EntityIdFactory entityIdFactory;

    /**
     * Default constructor for injection.
     */
    @Inject
    public CryptoTransferValidator(final EntityIdFactory entityIdFactory) {
        // For Dagger injection
        this.entityIdFactory = entityIdFactory;
    }

    /**
     * Performs pure checks that validates basic fields in the crypto transfer transaction.
     *
     * @param op the crypto transfer transaction body
     * @throws PreCheckException if any of the checks fail
     */
    public void pureChecks(@NonNull final CryptoTransferTransactionBody op) throws PreCheckException {
        final var acctAmounts = op.transfersOrElse(TransferList.DEFAULT).accountAmounts();
        validateTruePreCheck(isNetZeroAdjustment(acctAmounts), INVALID_ACCOUNT_AMOUNTS);

        final var uniqueAcctIds = new HashSet<AccountID>();
        // Validate hbar transfers
        for (final AccountAmount acctAmount : acctAmounts) {
            validateTruePreCheck(acctAmount.hasAccountID(), INVALID_ACCOUNT_ID);
            final var acctId = validateAccountID(acctAmount.accountIDOrThrow(), null);
            uniqueAcctIds.add(acctId);
            validateFalsePreCheck(hasApprovalAndHookExecution(acctAmount), CANNOT_SET_HOOKS_AND_APPROVAL);
        }
        validateFalsePreCheck(uniqueAcctIds.size() < acctAmounts.size(), ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);

        validateTokenTransfers(op.tokenTransfers(), AllowanceStrategy.ALLOWANCES_ALLOWED);
    }

    /**
     * All validations needed for the crypto transfer operation, that include state or config.
     *
     * @param op the crypto transfer operation
     * @param ledgerConfig the ledger config
     * @param accountsConfig the accounts config
     * @param hooksConfig
     */
    public void validateSemantics(
            @NonNull final CryptoTransferTransactionBody op,
            @NonNull final LedgerConfig ledgerConfig,
            @NonNull final AccountsConfig accountsConfig,
            @NonNull final HooksConfig hooksConfig) {
        final var transfers = op.transfersOrElse(TransferList.DEFAULT);
        // validate hooks are enabled if hooks are present in the transaction
        if (hasHooks(op)) {
            validateTrue(hooksConfig.hooksEnabled(), HOOKS_NOT_ENABLED);
        }

        // Validate that there aren't too many hbar transfers
        final var hbarTransfers = transfers.accountAmounts();

        // If the payer is node rewards account, we are dispatching synthetic node rewards. So skip checking the limits.
        if (hbarTransfers.size() > ledgerConfig.transfersMaxLen()) {
            final var nodeRewardAccountId = entityIdFactory.newAccountId(accountsConfig.nodeRewardAccount());
            validateTrue(
                    hbarTransfers.stream()
                            .filter(aa -> aa.amount() < 0)
                            .anyMatch(aa -> nodeRewardAccountId.equals(aa.accountID())),
                    TRANSFER_LIST_SIZE_LIMIT_EXCEEDED);
        }

        // Validate that allowances are enabled, or that no hbar transfers are an allowance transfer

        // The loop below will validate the counts for token transfers (both fungible and non-fungible)
        final var tokenTransfers = op.tokenTransfers();
        var totalFungibleTransfers = 0;
        var totalNftTransfers = 0;
        for (final TokenTransferList tokenTransfer : tokenTransfers) {
            // Validate the fungible token transfer(s) (if present)
            final var fungibleTransfers = tokenTransfer.transfers();
            totalFungibleTransfers += fungibleTransfers.size();

            // Validate the nft transfer(s) (if present)
            final var nftTransfers = tokenTransfer.nftTransfers();
            totalNftTransfers += nftTransfers.size();

            // Verify that the current total number of (counted) fungible transfers does not exceed the limit
            validateTrue(
                    totalFungibleTransfers <= ledgerConfig.tokenTransfersMaxLen(),
                    TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED);
            // Verify that the current total number of (counted) nft transfers does not exceed the limit
            validateTrue(totalNftTransfers <= ledgerConfig.nftTransfersMaxLen(), BATCH_SIZE_LIMIT_EXCEEDED);
        }
    }

    public static void validateTokenTransfers(
            final List<TokenTransferList> tokenTransfers, final AllowanceStrategy allowanceStrategy)
            throws PreCheckException {
        // Validate token transfers
        final var tokenIds = new HashSet<TokenID>();
        for (final TokenTransferList tokenTransfer : tokenTransfers) {
            final var tokenID = tokenTransfer.token();
            tokenIds.add(tokenID);
            validateTruePreCheck(tokenID != null && !tokenID.equals(TokenID.DEFAULT), INVALID_TOKEN_ID);

            // Validate the fungible transfers
            final var uniqueTokenAcctIds = new HashSet<AccountID>();
            validateNonDuplicateFungibleTransfers(tokenTransfer.transfers(), uniqueTokenAcctIds, allowanceStrategy);
            // Validate the nft transfers
            final var nftIds = new HashSet<Long>();
            validateNftTransfers(tokenTransfer.nftTransfers(), nftIds, allowanceStrategy);
            // Verify that one and only one of the two types of transfers (fungible or non-fungible) is present
            validateFalsePreCheck(
                    uniqueTokenAcctIds.isEmpty() && nftIds.isEmpty(), EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS);
        }
        validateFalsePreCheck(tokenIds.size() < tokenTransfers.size(), TOKEN_ID_REPEATED_IN_TOKEN_LIST);
    }

    public static void validateNonDuplicateFungibleTransfers(
            final List<AccountAmount> fungibleTransfers,
            final Set<AccountID> uniqueTokenAcctIds,
            final AllowanceStrategy allowanceStrategy)
            throws PreCheckException {
        validateTruePreCheck(isNetZeroAdjustment(fungibleTransfers), TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN);
        boolean nonZeroFungibleValueFound = false;
        for (final AccountAmount acctAmount : fungibleTransfers) {
            if (allowanceStrategy.equals(AllowanceStrategy.ALLOWANCES_REJECTED)) {
                validateFalsePreCheck(acctAmount.isApproval(), NOT_SUPPORTED);
            }
            validateTruePreCheck(acctAmount.hasAccountID(), INVALID_TRANSFER_ACCOUNT_ID);
            uniqueTokenAcctIds.add(acctAmount.accountIDOrThrow());
            if (!nonZeroFungibleValueFound && acctAmount.amount() != 0) {
                nonZeroFungibleValueFound = true;
            }
            validateFalsePreCheck(hasApprovalAndHookExecution(acctAmount), CANNOT_SET_HOOKS_AND_APPROVAL);
        }
        validateFalsePreCheck(
                uniqueTokenAcctIds.size() < fungibleTransfers.size(), ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);
    }

    private static boolean hasApprovalAndHookExecution(final AccountAmount acctAmount) {
        return acctAmount.isApproval()
                && (acctAmount.hasPreTxAllowanceHook() || acctAmount.hasPrePostTxAllowanceHook());
    }

    private static boolean hasApprovalAndHookExecution(final NftTransfer nftTransfer) {
        return nftTransfer.isApproval()
                && (nftTransfer.hasPreTxSenderAllowanceHook() || nftTransfer.hasPrePostTxSenderAllowanceHook());
    }

    public static void validateNftTransfers(
            final List<NftTransfer> nftTransfers, final Set<Long> nftIds, final AllowanceStrategy allowanceStrategy)
            throws PreCheckException {
        for (final NftTransfer nftTransfer : nftTransfers) {
            if (allowanceStrategy.equals(AllowanceStrategy.ALLOWANCES_REJECTED)) {
                validateFalsePreCheck(nftTransfer.isApproval(), NOT_SUPPORTED);
            }
            validateTruePreCheck(nftTransfer.serialNumber() > 0, INVALID_TOKEN_NFT_SERIAL_NUMBER);
            validateTruePreCheck(nftTransfer.hasSenderAccountID(), INVALID_TRANSFER_ACCOUNT_ID);
            validateTruePreCheck(nftTransfer.hasReceiverAccountID(), INVALID_TRANSFER_ACCOUNT_ID);
            validateFalsePreCheck(
                    !nftIds.isEmpty() && nftIds.contains(nftTransfer.serialNumber()), INVALID_ACCOUNT_AMOUNTS);
            validateFalsePreCheck(
                    nftTransfer.senderAccountIDOrThrow().equals(nftTransfer.receiverAccountID()),
                    ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);
            validateFalsePreCheck(hasApprovalAndHookExecution(nftTransfer), CANNOT_SET_HOOKS_AND_APPROVAL);
            nftIds.add(nftTransfer.serialNumber());
        }
    }

    private static boolean isNetZeroAdjustment(@NonNull final List<AccountAmount> adjusts) {
        var net = ZERO;
        for (var adjust : adjusts) {
            net = net.add(BigInteger.valueOf(adjust.amount()));
        }
        return net.equals(ZERO);
    }

    public static boolean hasHooks(final @NonNull CryptoTransferTransactionBody op) {
        for (final AccountAmount aa : op.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
            if (aa.hasPreTxAllowanceHook() || aa.hasPrePostTxAllowanceHook()) {
                return true;
            }
        }
        for (final TokenTransferList ttl : op.tokenTransfers()) {
            for (final AccountAmount aa : ttl.transfers()) {
                if (aa.hasPreTxAllowanceHook() || aa.hasPrePostTxAllowanceHook()) {
                    return true;
                }
            }
            for (final NftTransfer nft : ttl.nftTransfers()) {
                if (nft.hasPreTxSenderAllowanceHook()
                        || nft.hasPrePostTxSenderAllowanceHook()
                        || nft.hasPreTxReceiverAllowanceHook()
                        || nft.hasPrePostTxReceiverAllowanceHook()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Enum to specify the strategy for handling allowances. For airdrops, currently we don't support allowances.
     * For crypto transfer the allowances should be supported.
     */
    public enum AllowanceStrategy {
        ALLOWANCES_ALLOWED,
        ALLOWANCES_REJECTED
    }
}
