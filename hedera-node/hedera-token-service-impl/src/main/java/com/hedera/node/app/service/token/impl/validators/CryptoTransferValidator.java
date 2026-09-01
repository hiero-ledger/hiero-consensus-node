// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BAD_HOOK_REQUEST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CANNOT_SET_HOOKS_AND_APPROVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOKS_EXECUTIONS_REQUIRE_TOP_LEVEL_CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOKS_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOO_MANY_HOOK_INVOCATIONS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.hasHookExecutions;
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
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.HooksConfig;
import com.hedera.node.config.data.LedgerConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
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

        final var uniqueAcctIds = acctAmounts.size() >= 5 ? HashSet.<AccountID>newHashSet(acctAmounts.size()) : null;
        AccountID acctId0 = null;
        AccountID acctId1 = null;
        AccountID acctId2 = null;
        AccountID acctId3 = null;
        var hasRepeatedAccount = false;
        // Validate hbar transfers, delaying any duplicate error until all other validations have run
        for (int i = 0; i < acctAmounts.size(); i++) {
            final var acctAmount = acctAmounts.get(i);
            validateTruePreCheck(acctAmount.hasAccountID(), INVALID_ACCOUNT_ID);
            final var acctId = validateAccountID(acctAmount.accountIDOrThrow(), null);
            if (uniqueAcctIds != null) {
                uniqueAcctIds.add(acctId);
            } else {
                switch (i) {
                    case 0 -> acctId0 = acctId;
                    case 1 -> {
                        hasRepeatedAccount |= acctId.equals(acctId0);
                        acctId1 = acctId;
                    }
                    case 2 -> {
                        hasRepeatedAccount |= acctId.equals(acctId0) || acctId.equals(acctId1);
                        acctId2 = acctId;
                    }
                    case 3 -> {
                        hasRepeatedAccount |=
                                acctId.equals(acctId0) || acctId.equals(acctId1) || acctId.equals(acctId2);
                        acctId3 = acctId;
                    }
                    default -> throw new AssertionError("Unexpected small hbar transfer count");
                }
            }
            validateFalsePreCheck(hasApprovalAndHookExecution(acctAmount), CANNOT_SET_HOOKS_AND_APPROVAL);
        }
        validateFalsePreCheck(
                uniqueAcctIds != null ? uniqueAcctIds.size() < acctAmounts.size() : hasRepeatedAccount,
                ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);

        validateTokenTransfers(op.tokenTransfers(), AllowanceStrategy.ALLOWANCES_ALLOWED);
    }

    /**
     * All validations needed for the crypto transfer operation, that include state or config.
     *
     * @param op the crypto transfer operation
     * @param ledgerConfig the ledger config
     * @param accountsConfig the accounts config
     * @param hooksConfig the hooks config
     * @param category the transaction category
     * @param payer the payer account ID
     */
    public void validateSemantics(
            @NonNull final CryptoTransferTransactionBody op,
            @NonNull final LedgerConfig ledgerConfig,
            @NonNull final AccountsConfig accountsConfig,
            @NonNull final HooksConfig hooksConfig,
            final HandleContext.TransactionCategory category,
            @NonNull final AccountID payer) {
        final var transfers = op.transfersOrElse(TransferList.DEFAULT);
        // validate hooks are enabled if hooks are present in the transaction
        if (hasHookExecutions(op)) {
            validateTrue(hooksConfig.hooksEnabled(), HOOKS_NOT_ENABLED);
            validateTrue(
                    category.equals(HandleContext.TransactionCategory.USER),
                    HOOKS_EXECUTIONS_REQUIRE_TOP_LEVEL_CRYPTO_TRANSFER);
            validateHookGasLimitAndInvocations(op, hooksConfig);
        }

        // Validate that there aren't too many hbar transfers
        final var hbarTransfers = transfers.accountAmounts();
        // If the debit account is node rewards account or fee collection account, we are dispatching synthetic
        // node rewards or node fee distributions. So skip checking the limits.
        if (hbarTransfers.size() > ledgerConfig.transfersMaxLen()) {
            // One special case allowing the system admin to distribute a large number of fee payments or rewards
            if (entityIdFactory.newAccountId(accountsConfig.systemAdmin()).equals(payer)) {
                final var nodeRewardAccountId = entityIdFactory.newAccountId(accountsConfig.nodeRewardAccount());
                final var feeCollectionAccountId = entityIdFactory.newAccountId(accountsConfig.feeCollectionAccount());
                validateTrue(
                        hbarTransfers.stream()
                                .filter(aa -> aa.amount() < 0)
                                .anyMatch(aa -> (nodeRewardAccountId.equals(aa.accountID())
                                        || feeCollectionAccountId.equals(aa.accountID()))),
                        TRANSFER_LIST_SIZE_LIMIT_EXCEEDED);
            } else {
                throw new HandleException(TRANSFER_LIST_SIZE_LIMIT_EXCEEDED);
            }
        }

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
            final var feeCollectionAccount = entityIdFactory.newAccountId(accountsConfig.feeCollectionAccount());
            //  Verify that no credits are going to the fee collection account.
            //  We validate hbar transfers in AdjustHbarChangesStep
            validateNoCreditsToFeeCollectionAccount(tokenTransfer, feeCollectionAccount);
        }
    }

    /**
     * Verify that no credits are going to the fee collection account
     *
     * @param transferList token transfer list
     * @param feeCollectionAccount fee collection account
     */
    private void validateNoCreditsToFeeCollectionAccount(
            final TokenTransferList transferList, final AccountID feeCollectionAccount) {
        validateTrue(
                transferList.transfers().stream()
                        .noneMatch(aa -> (aa.amount() > 0 && aa.accountID().equals(feeCollectionAccount))),
                TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED);
        validateTrue(
                transferList.nftTransfers().stream()
                        .noneMatch(
                                nftTransfer -> nftTransfer.receiverAccountID().equals(feeCollectionAccount)),
                TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED);
    }

    private void validateHookGasLimitAndInvocations(
            final CryptoTransferTransactionBody op, final HooksConfig hooksConfig) {
        final var gasLimit = hooksConfig.evmHookIntrinsicGasCost();
        var numHookInvocations = 0;
        for (final var aa : op.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
            numHookInvocations += validateFungibleTransferHooks(aa, gasLimit);
        }
        for (final var tokenTransfer : op.tokenTransfers()) {
            for (final var aa : tokenTransfer.transfers()) {
                numHookInvocations += validateFungibleTransferHooks(aa, gasLimit);
            }
            for (final var nftTransfer : tokenTransfer.nftTransfers()) {
                numHookInvocations += validateNftTransferHooks(nftTransfer, gasLimit);
            }
        }
        validateTrue(numHookInvocations <= hooksConfig.maxHookInvocationsPerTransaction(), TOO_MANY_HOOK_INVOCATIONS);
    }

    private int validateNftTransferHooks(final NftTransfer nftTransfer, final int gasLimit) {
        int numInvocations = 0;
        if (nftTransfer.hasPreTxSenderAllowanceHook()) {
            numInvocations++;
            validateTrue(nftTransfer.preTxSenderAllowanceHookOrThrow().hasEvmHookCall(), BAD_HOOK_REQUEST);
            validateTrue(
                    nftTransfer
                                    .preTxSenderAllowanceHookOrThrow()
                                    .evmHookCallOrThrow()
                                    .gasLimit()
                            > gasLimit,
                    INSUFFICIENT_GAS);
        }
        if (nftTransfer.hasPrePostTxSenderAllowanceHook()) {
            numInvocations += 2;
            validateTrue(nftTransfer.prePostTxSenderAllowanceHookOrThrow().hasEvmHookCall(), BAD_HOOK_REQUEST);
            validateTrue(
                    nftTransfer
                                    .prePostTxSenderAllowanceHookOrThrow()
                                    .evmHookCallOrThrow()
                                    .gasLimit()
                            > gasLimit,
                    INSUFFICIENT_GAS);
        }
        if (nftTransfer.hasPreTxReceiverAllowanceHook()) {
            numInvocations++;
            validateTrue(nftTransfer.preTxReceiverAllowanceHookOrThrow().hasEvmHookCall(), BAD_HOOK_REQUEST);
            validateTrue(
                    nftTransfer
                                    .preTxReceiverAllowanceHookOrThrow()
                                    .evmHookCallOrThrow()
                                    .gasLimit()
                            > gasLimit,
                    INSUFFICIENT_GAS);
        }
        if (nftTransfer.hasPrePostTxReceiverAllowanceHook()) {
            numInvocations += 2;
            validateTrue(nftTransfer.prePostTxReceiverAllowanceHookOrThrow().hasEvmHookCall(), BAD_HOOK_REQUEST);
            validateTrue(
                    nftTransfer
                                    .prePostTxReceiverAllowanceHookOrThrow()
                                    .evmHookCallOrThrow()
                                    .gasLimit()
                            > gasLimit,
                    INSUFFICIENT_GAS);
        }
        return numInvocations;
    }

    private static int validateFungibleTransferHooks(final AccountAmount aa, final int gasLimit) {
        int numInvocations = 0;
        if (aa.hasPreTxAllowanceHook()) {
            numInvocations++;
            validateTrue(aa.preTxAllowanceHookOrThrow().hasEvmHookCall(), BAD_HOOK_REQUEST);
            validateTrue(aa.preTxAllowanceHookOrThrow().evmHookCallOrThrow().gasLimit() > gasLimit, INSUFFICIENT_GAS);
        }
        if (aa.hasPrePostTxAllowanceHook()) {
            numInvocations += 2;
            validateTrue(aa.prePostTxAllowanceHookOrThrow().hasEvmHookCall(), BAD_HOOK_REQUEST);
            validateTrue(
                    aa.prePostTxAllowanceHookOrThrow().evmHookCallOrThrow().gasLimit() > gasLimit, INSUFFICIENT_GAS);
        }
        return numInvocations;
    }

    public static void validateTokenTransfers(
            final List<TokenTransferList> tokenTransfers, final AllowanceStrategy allowanceStrategy)
            throws PreCheckException {
        // Validate token transfers
        final var tokenIds = tokenTransfers.size() >= 5 ? HashSet.<TokenID>newHashSet(tokenTransfers.size()) : null;
        TokenID tokenId0 = null;
        TokenID tokenId1 = null;
        TokenID tokenId2 = null;
        TokenID tokenId3 = null;
        var hasRepeatedToken = false;
        for (int i = 0; i < tokenTransfers.size(); i++) {
            final var tokenTransfer = tokenTransfers.get(i);
            final var tokenID = tokenTransfer.token();
            if (tokenIds != null) {
                tokenIds.add(tokenID);
            } else {
                switch (i) {
                    case 0 -> tokenId0 = tokenID;
                    case 1 -> {
                        hasRepeatedToken |= tokenID != null && tokenID.equals(tokenId0);
                        tokenId1 = tokenID;
                    }
                    case 2 -> {
                        hasRepeatedToken |= tokenID != null && (tokenID.equals(tokenId0) || tokenID.equals(tokenId1));
                        tokenId2 = tokenID;
                    }
                    case 3 -> {
                        hasRepeatedToken |= tokenID != null
                                && (tokenID.equals(tokenId0) || tokenID.equals(tokenId1) || tokenID.equals(tokenId2));
                        tokenId3 = tokenID;
                    }
                    default -> throw new AssertionError("Unexpected small token transfer count");
                }
            }
            validateTruePreCheck(tokenID != null && !tokenID.equals(TokenID.DEFAULT), INVALID_TOKEN_ID);

            // Validate the fungible transfers
            final var fungibleTransfers = tokenTransfer.transfers();
            validateNonDuplicateFungibleTransfers(fungibleTransfers, allowanceStrategy);
            // Validate the nft transfers
            final var nftTransfers = tokenTransfer.nftTransfers();
            validateNftTransfers(nftTransfers, allowanceStrategy);
            // Verify that one and only one of the two types of transfers (fungible or non-fungible) is present
            validateFalsePreCheck(
                    fungibleTransfers.isEmpty() && nftTransfers.isEmpty(), EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS);
        }
        validateFalsePreCheck(
                tokenIds != null ? tokenIds.size() < tokenTransfers.size() : hasRepeatedToken,
                TOKEN_ID_REPEATED_IN_TOKEN_LIST);
    }

    private static void validateNonDuplicateFungibleTransfers(
            final List<AccountAmount> fungibleTransfers, final AllowanceStrategy allowanceStrategy)
            throws PreCheckException {
        validateTruePreCheck(isNetZeroAdjustment(fungibleTransfers), TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN);
        final var uniqueTokenAcctIds =
                fungibleTransfers.size() >= 5 ? HashSet.<AccountID>newHashSet(fungibleTransfers.size()) : null;
        AccountID acctId0 = null;
        AccountID acctId1 = null;
        AccountID acctId2 = null;
        AccountID acctId3 = null;
        var hasRepeatedAccount = false;
        boolean nonZeroFungibleValueFound = false;
        for (int i = 0; i < fungibleTransfers.size(); i++) {
            final var acctAmount = fungibleTransfers.get(i);
            if (allowanceStrategy.equals(AllowanceStrategy.ALLOWANCES_REJECTED)) {
                validateFalsePreCheck(acctAmount.isApproval(), NOT_SUPPORTED);
            }
            validateTruePreCheck(acctAmount.hasAccountID(), INVALID_TRANSFER_ACCOUNT_ID);
            final var acctId = acctAmount.accountIDOrThrow();
            if (uniqueTokenAcctIds != null) {
                uniqueTokenAcctIds.add(acctId);
            } else {
                switch (i) {
                    case 0 -> acctId0 = acctId;
                    case 1 -> {
                        hasRepeatedAccount |= acctId.equals(acctId0);
                        acctId1 = acctId;
                    }
                    case 2 -> {
                        hasRepeatedAccount |= acctId.equals(acctId0) || acctId.equals(acctId1);
                        acctId2 = acctId;
                    }
                    case 3 -> {
                        hasRepeatedAccount |=
                                acctId.equals(acctId0) || acctId.equals(acctId1) || acctId.equals(acctId2);
                        acctId3 = acctId;
                    }
                    default -> throw new AssertionError("Unexpected small fungible transfer count");
                }
            }
            if (!nonZeroFungibleValueFound && acctAmount.amount() != 0) {
                nonZeroFungibleValueFound = true;
            }
            validateFalsePreCheck(hasApprovalAndHookExecution(acctAmount), CANNOT_SET_HOOKS_AND_APPROVAL);
        }
        validateFalsePreCheck(
                uniqueTokenAcctIds != null ? uniqueTokenAcctIds.size() < fungibleTransfers.size() : hasRepeatedAccount,
                ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);
    }

    private static boolean hasApprovalAndHookExecution(final AccountAmount acctAmount) {
        return acctAmount.isApproval()
                && (acctAmount.hasPreTxAllowanceHook() || acctAmount.hasPrePostTxAllowanceHook());
    }

    private static boolean hasApprovalAndHookExecution(final NftTransfer nftTransfer) {
        return nftTransfer.isApproval()
                && (nftTransfer.hasPreTxSenderAllowanceHook() || nftTransfer.hasPrePostTxSenderAllowanceHook());
    }

    private static void validateNftTransfers(
            final List<NftTransfer> nftTransfers, final AllowanceStrategy allowanceStrategy) throws PreCheckException {
        final var nftIds = nftTransfers.size() >= 5 ? HashSet.<Long>newHashSet(nftTransfers.size()) : null;
        long serial0 = 0;
        long serial1 = 0;
        long serial2 = 0;
        for (int i = 0; i < nftTransfers.size(); i++) {
            final var nftTransfer = nftTransfers.get(i);
            if (allowanceStrategy.equals(AllowanceStrategy.ALLOWANCES_REJECTED)) {
                validateFalsePreCheck(nftTransfer.isApproval(), NOT_SUPPORTED);
            }
            final var serialNumber = nftTransfer.serialNumber();
            validateTruePreCheck(serialNumber > 0, INVALID_TOKEN_NFT_SERIAL_NUMBER);
            validateTruePreCheck(nftTransfer.hasSenderAccountID(), INVALID_TRANSFER_ACCOUNT_ID);
            validateTruePreCheck(nftTransfer.hasReceiverAccountID(), INVALID_TRANSFER_ACCOUNT_ID);
            final boolean hasRepeatedSerial;
            if (nftIds != null) {
                hasRepeatedSerial = !nftIds.isEmpty() && nftIds.contains(serialNumber);
            } else {
                hasRepeatedSerial = switch (i) {
                    case 0 -> false;
                    case 1 -> serialNumber == serial0;
                    case 2 -> serialNumber == serial0 || serialNumber == serial1;
                    case 3 -> serialNumber == serial0 || serialNumber == serial1 || serialNumber == serial2;
                    default -> throw new AssertionError("Unexpected small NFT transfer count");
                };
            }
            validateFalsePreCheck(hasRepeatedSerial, INVALID_ACCOUNT_AMOUNTS);
            validateFalsePreCheck(
                    nftTransfer.senderAccountIDOrThrow().equals(nftTransfer.receiverAccountID()),
                    ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);
            validateFalsePreCheck(hasApprovalAndHookExecution(nftTransfer), CANNOT_SET_HOOKS_AND_APPROVAL);
            if (nftIds != null) {
                nftIds.add(serialNumber);
            } else {
                switch (i) {
                    case 0 -> serial0 = serialNumber;
                    case 1 -> serial1 = serialNumber;
                    case 2 -> serial2 = serialNumber;
                    case 3 -> {
                        // No later transfer needs the fourth serial.
                    }
                    default -> throw new AssertionError("Unexpected small NFT transfer count");
                }
            }
        }
    }

    private static boolean isNetZeroAdjustment(@NonNull final List<AccountAmount> adjusts) {
        var net = ZERO;
        for (var adjust : adjusts) {
            net = net.add(BigInteger.valueOf(adjust.amount()));
        }
        return net.equals(ZERO);
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
