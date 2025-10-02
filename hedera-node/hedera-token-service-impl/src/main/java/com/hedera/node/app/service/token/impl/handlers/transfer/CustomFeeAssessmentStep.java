// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_CHARGING_EXCEEDED_MAX_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NFT_TRANSFERS_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.TokenType.FUNGIBLE_COMMON;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessmentResult.HBAR_TOKEN_ID;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.TokenValidations.PERMIT_PAUSED;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.TRANSACTION_FIXED_FEE;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.FixedCustomFee;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessedFeeWithPayerDebits;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessmentResult;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFeeAssessor;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFixedFeeAssessor;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFractionalFeeAssessor;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomRoyaltyFeeAssessor;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.base.utility.Pair;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Charges custom fees for the crypto transfer operation.
 * Custom fees can be a Fixed Fee (HBAR or HTS), Fractional Fee or Royalty Fee.
 * Any fixed HTS fees that are not self-denominated can trigger next level of custom fees assessment.
 * Fractional fees and Royalty fees are not recursive.
 * When assessing custom fees in this approach, we build list of transaction bodies that include assessed custom fees.
 * We also build list of assessed custom fees to be added to the record.
 * We do this in 2 steps:
 * 1. Assess custom fees for the transaction body given as input (Level-0 Body)
 * 2. If there are any fractional fees, adjust the assessed changes in the Level-0 Body
 * 3. Any non-self denominated fixed (HBAR or HTS) fees, assess them and create Level-1 Body. But any
 * self denominated fees will be adjusted in Level- 0 Body (since they can't trigger further custom fee charging.)
 * 4.Any royalty fees which are not self denominated will be added to level-1 body.
 */
public class CustomFeeAssessmentStep {
    private final CryptoTransferTransactionBody op;
    private final CustomFeeAssessor customFeeAssessor;
    private int levelNum = 0;
    private static final int MAX_PLAUSIBLE_LEVEL_NUM = 10;
    private static final Logger log = LogManager.getLogger(CustomFeeAssessmentStep.class);

    /**
     * Constructs a {@link CustomFeeAssessmentStep} for the given transaction body.
     * @param op the transaction body
     */
    public CustomFeeAssessmentStep(@NonNull final CryptoTransferTransactionBody op) {
        this.op = op;
        final var fixedFeeAssessor = new CustomFixedFeeAssessor();
        final var fractionalFeeAssessor = new CustomFractionalFeeAssessor(fixedFeeAssessor);
        final var royaltyFeeAssessor = new CustomRoyaltyFeeAssessor(fixedFeeAssessor);
        customFeeAssessor = new CustomFeeAssessor(fixedFeeAssessor, fractionalFeeAssessor, royaltyFeeAssessor);
        customFeeAssessor.calculateAndSetInitialNftChanges(op);
    }

    /**
     * Given a transaction body, assess custom fees for the crypto transfer operation.
     * This is called in Ingest and handle to fetch fees for CryptoTransfer transaction.
     *
     * @param feeContext - fee context
     * @return - list of assessed custom fees
     */
    public List<AssessedCustomFee> assessNumberOfCustomFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);

        final var tokenStore = feeContext.readableStore(ReadableTokenStore.class);
        final var tokenRelStore = feeContext.readableStore(ReadableTokenRelationStore.class);
        final var readableStore = feeContext.readableStore(ReadableAccountStore.class);
        final var config = feeContext.configuration();
        final var result = assessFees(tokenStore, tokenRelStore, config, readableStore, AccountID::hasAlias);
        return result.assessedCustomFees().stream()
                .map(AssessedFeeWithPayerDebits::assessedCustomFee)
                .toList();
    }

    /**
     * Given a transaction body, assess custom fees for the crypto transfer operation.
     * It iterates through the token transfer list and assesses custom fees for each token transfer.
     * It creates 2 new hashmaps with assessed hbar fees and assessed token fees.
     * It is possible the assessed token custom fees could trigger custom fee again.
     * So, we repeat the process for the assessed custom fees one more time.
     *
     * @param transferContext - transfer context
     * @return - transaction body with assessed custom fees
     */
    public List<CryptoTransferTransactionBody> assessCustomFees(@NonNull final TransferContext transferContext) {
        requireNonNull(transferContext);

        final var handleContext = transferContext.getHandleContext();
        final var storeFactory = handleContext.storeFactory();
        final var tokenStore = storeFactory.readableStore(ReadableTokenStore.class);
        final var tokenRelStore = storeFactory.readableStore(ReadableTokenRelationStore.class);
        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);
        final var config = handleContext.configuration();
        final Predicate<AccountID> autoCreationTest;
        if (transferContext.isEnforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments()) {
            autoCreationTest = transferContext.resolutions().values()::contains;
        } else {
            autoCreationTest = accountId -> false;
        }
        // assess custom fees
        final var result = assessFees(tokenStore, tokenRelStore, config, accountStore, autoCreationTest);

        // check if the current operation is a dispatch for paying a transaction fixed fee
        final var txnFeeMetadata = transferContext
                .getHandleContext()
                .dispatchMetadata()
                .getMetadata(TRANSACTION_FIXED_FEE, FixedCustomFee.class);
        if (txnFeeMetadata.isPresent()) {
            final var transactionFixedFee = txnFeeMetadata.get();
            final var payer = transferContext.getHandleContext().payer();
            final var assessmentResult = new AssessmentResult(emptyList(), emptyList());

            // when dispatching crypto transfer for charging custom fees,
            // we still need to set the transfer as assessed
            customFeeAssessor.setTransactionFeesAsAssessed(payer, transactionFixedFee, assessmentResult);
            result.assessedCustomFees.addAll(assessmentResult.getAssessedFeesWithPayerDebits());
        }

        result.assessedCustomFees().forEach(transferContext::addToAssessedCustomFee);
        customFeeAssessor.resetInitialNftChanges();
        return result.assessedTxns();
    }

    /**
     * Given a transaction body, assess custom fees for the crypto transfer operation.
     * It iterates through the token transfer list and assesses custom fees for each token transfer
     * It creates 2 new hashmaps with assessed hbar fees and assessed token fees.
     * It is possible the assessed token custom fees could trigger custom fee again.
     * So, we repeat the process for the assessed custom fees one more time.
     * This will be run once in handle and once in ingest to get fees for CryptoTransfer transaction.
     *
     * @param tokenStore - token store
     * @param tokenRelStore - token relation store
     * @param config - configuration
     * @param accountStore - account store
     * @param autoCreationTest - predicate to test if account id is being auto created
     * @return - transaction body with assessed custom fees
     */
    public CustomFeeAssessmentResult assessFees(
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final ReadableTokenRelationStore tokenRelStore,
            @NonNull final Configuration config,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final Predicate<AccountID> autoCreationTest) {
        final var tokensConfig = config.getConfigData(TokensConfig.class);
        final var maxCustomFeeDepth = tokensConfig.maxCustomFeeDepth();

        // list of total assessed custom fees to be added to the record
        final List<AssessedFeeWithPayerDebits> customFeesAssessed = new ArrayList<>();
        // the transaction to be assessed
        var txnToAssess = op;
        // list of assessed transactions, to be fed into further steps
        final List<CryptoTransferTransactionBody> assessedTxns = new ArrayList<>();

        // The first assessment inputs
        var tokenTransfers = op.tokenTransfers();
        var hbarTransfers = op.transfersOrElse(TransferList.DEFAULT).accountAmounts();

        do {
            validateTrue(levelNum <= maxCustomFeeDepth, CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH);
            // The result after each assessment
            final var result = assessCustomFeesFrom(
                    hbarTransfers, tokenTransfers, tokenStore, tokenRelStore, accountStore, autoCreationTest);
            // when there are adjustments made to given transaction, need to re-build the transaction
            if (!result.getAssessedFeesWithPayerDebits().isEmpty()) {
                final var modifiedInputBody = changedInputTxn(txnToAssess, result);
                assessedTxns.add(modifiedInputBody);
                customFeesAssessed.addAll(result.getAssessedFeesWithPayerDebits());
                // build body from assessed custom fees to be fed to next level of assessment
                txnToAssess = buildBodyFromAdjustments(result);
            } else {
                // If there are no assessed custom fee for the input transaction, this will be added to the
                // list of assessed and exit since this doesn't trigger any more custom fees
                break;
            }
            tokenTransfers = txnToAssess.tokenTransfers();
            hbarTransfers = txnToAssess.transfersOrElse(TransferList.DEFAULT).accountAmounts();
            levelNum++;
        } while (!tokenTransfers.isEmpty() && levelNum <= MAX_PLAUSIBLE_LEVEL_NUM);

        if (levelNum > MAX_PLAUSIBLE_LEVEL_NUM) {
            log.error("Recursive charging exceeded maximum plausible depth for transaction {}", op);
            throw new IllegalStateException("Custom fee charging exceeded max recursion depth");
        }

        // If the last charging level assessed fees, we should include them for further steps of CryptoTransfer
        if (!hbarTransfers.isEmpty() || !tokenTransfers.isEmpty()) {
            assessedTxns.add(txnToAssess);
        }
        // If custom fee adjustments were incurred, we need to validate the total number
        // of balance adjustments and ownership changes is within our limits
        if (!customFeesAssessed.isEmpty()) {
            final var maxBalanceChanges =
                    config.getConfigData(LedgerConfig.class).xferBalanceChangesMaxLen();
            validateTrue(
                    numUniqueAdjustmentsIn(assessedTxns) <= maxBalanceChanges,
                    CUSTOM_FEE_CHARGING_EXCEEDED_MAX_ACCOUNT_AMOUNTS);
        }
        return new CustomFeeAssessmentResult(assessedTxns, customFeesAssessed);
    }

    private int numUniqueAdjustmentsIn(final List<CryptoTransferTransactionBody> assessedTxns) {
        var numOwnershipChanges = 0;
        final Set<AccountID> uniqueHbarAdjustments = new HashSet<>();
        final Set<Pair<AccountID, TokenID>> uniqueTokenAdjustments = new HashSet<>();
        for (final var txn : assessedTxns) {
            for (final var aa : txn.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
                uniqueHbarAdjustments.add(aa.accountID());
            }
            for (final var xfer : txn.tokenTransfers()) {
                for (final var aa : xfer.transfers()) {
                    uniqueTokenAdjustments.add(Pair.of(aa.accountID(), xfer.token()));
                }
                numOwnershipChanges += xfer.nftTransfers().size();
            }
        }
        return numOwnershipChanges + uniqueHbarAdjustments.size() + uniqueTokenAdjustments.size();
    }

    /**
     * Record to hold the result of custom fee assessment.
     *
     * @param assessedTxns - list of assessed cryptoTransfer transactions
     * @param assessedCustomFees - list of assessed custom fees
     */
    public record CustomFeeAssessmentResult(
            @NonNull List<CryptoTransferTransactionBody> assessedTxns,
            @NonNull List<AssessedFeeWithPayerDebits> assessedCustomFees) {}

    private CryptoTransferTransactionBody changedInputTxn(
            final CryptoTransferTransactionBody op, final AssessmentResult result) {
        final var copy = op.copyBuilder();
        // Update transfer list
        final var changedHbarTransfers =
                result.getMutableInputBalanceAdjustments().get(HBAR_TOKEN_ID);
        final TransferList.Builder transferList = TransferList.newBuilder();
        final List<AccountAmount> hbarList =
                getRevisedAdjustments(op.transfersOrElse(TransferList.DEFAULT).accountAmounts(), changedHbarTransfers);
        copy.transfers(transferList.accountAmounts(hbarList).build());

        // Update token transfer lists
        final var changedFungibleTokenTransfers = result.getMutableInputBalanceAdjustments();
        final List<TokenTransferList> tokenTransferLists = new ArrayList<>();
        for (final var xfers : op.tokenTransfers()) {
            final var token = xfers.token();
            // If there are no changes for the token, leave its list untouched
            if (!changedFungibleTokenTransfers.containsKey(token)) {
                tokenTransferLists.add(xfers);
            } else {
                final var postAssessmentBalances = changedFungibleTokenTransfers.get(token);
                final var adjustsHere = xfers.transfers();
                final var includedNetNewChanges = postAssessmentBalances.size() > adjustsHere.size();
                if (includedNetNewChanges || balancesChangedBetween(adjustsHere, postAssessmentBalances)) {
                    final List<AccountAmount> newTransfers = getRevisedAdjustments(adjustsHere, postAssessmentBalances);
                    tokenTransferLists.add(
                            xfers.copyBuilder().transfers(newTransfers).build());
                } else {
                    tokenTransferLists.add(xfers);
                }
            }
        }
        copy.tokenTransfers(tokenTransferLists);
        return copy.build();
    }

    @NonNull
    private static List<AccountAmount> getRevisedAdjustments(
            final List<AccountAmount> adjustsHere, final Map<AccountID, Long> newBalances) {
        final List<AccountAmount> newTransfers = new ArrayList<>(adjustsHere.size());
        // First re-use the original transaction body to preserve any approvals or decimals that were set
        for (final var aa : adjustsHere) {
            final var newAmount = newBalances.getOrDefault(aa.accountID(), aa.amount());
            newTransfers.add(aa.copyBuilder().amount(newAmount).build());
            newBalances.remove(aa.accountID());
        }
        // Add any net-new custom fee adjustments (e.g. credits to fee collectors)
        for (final var entry : newBalances.entrySet()) {
            newTransfers.add(AccountAmount.newBuilder()
                    .accountID(entry.getKey())
                    .amount(entry.getValue())
                    .build());
        }
        return newTransfers;
    }

    private boolean balancesChangedBetween(
            @NonNull final List<AccountAmount> original, @NonNull final Map<AccountID, Long> postAssessmentBalances) {
        for (final var aa : original) {
            if (aa.amount() != postAssessmentBalances.getOrDefault(aa.accountID(), aa.amount())) {
                return true;
            }
        }
        return false;
    }

    private CryptoTransferTransactionBody buildBodyFromAdjustments(final AssessmentResult result) {
        final var hbarAdjustments = result.getHbarAdjustments();
        final var tokenAdjustments = result.getHtsAdjustments();

        final var newBuilder = CryptoTransferTransactionBody.newBuilder();
        final var transferList = TransferList.newBuilder();
        final List<AccountAmount> hbarList = new ArrayList<>();
        final List<TokenTransferList> tokenTransferLists = new ArrayList<>();

        for (final var entry : hbarAdjustments.entrySet()) {
            hbarList.add(AccountAmount.newBuilder()
                    .accountID(entry.getKey())
                    .amount(entry.getValue())
                    .build());
        }
        transferList.accountAmounts(hbarList);
        newBuilder.transfers(transferList.build());

        for (final var entry : tokenAdjustments.entrySet()) {
            final var tokenTransferList = TokenTransferList.newBuilder().token(entry.getKey());
            final List<AccountAmount> aaList = new ArrayList<>();
            for (final var valueEntry : entry.getValue().entrySet()) {
                aaList.add(AccountAmount.newBuilder()
                        .accountID(valueEntry.getKey())
                        .amount(valueEntry.getValue())
                        .build());
            }
            tokenTransferList.transfers(aaList);
            tokenTransferLists.add(tokenTransferList.build());
        }
        newBuilder.tokenTransfers(tokenTransferLists);
        return newBuilder.build();
    }

    private AssessmentResult assessCustomFeesFrom(
            @NonNull final List<AccountAmount> hbarTransfers,
            @NonNull final List<TokenTransferList> tokenTransfers,
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final ReadableTokenRelationStore tokenRelStore,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final Predicate<AccountID> autoCreationTest) {
        final var result = new AssessmentResult(tokenTransfers, hbarTransfers);

        for (final var xfer : tokenTransfers) {
            final var tokenId = xfer.tokenOrElse(TokenID.DEFAULT);
            final var ftTransfers = xfer.transfers();
            final var nftTransfers = xfer.nftTransfers();

            final var token = getIfUsable(tokenId, tokenStore, PERMIT_PAUSED);
            if (token.customFees().isEmpty()) {
                continue;
            }
            final boolean isFungible = token.tokenType().equals(FUNGIBLE_COMMON);

            for (final var aa : ftTransfers) {
                final var adjustment = aa.amount();
                validateFalse(
                        !isFungible && adjustment != 0, ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON);

                if (adjustment < 0) {
                    final var sender = aa.accountID();
                    // If sender for this adjustment is same as treasury for token
                    // then don't charge any custom fee. Since token treasuries are exempt from custom fees
                    if (token.treasuryAccountIdOrThrow().equals(sender)) {
                        continue;
                    }
                    customFeeAssessor.assess(
                            sender, token, null, result, tokenRelStore, accountStore, autoCreationTest);
                }
            }

            if (isFungible) {
                validateTrue(nftTransfers.isEmpty(), NFT_TRANSFERS_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE);
            }
            for (final var nftTransfer : nftTransfers) {
                if (token.treasuryAccountIdOrThrow().equals(nftTransfer.senderAccountID())) {
                    continue;
                }
                customFeeAssessor.assess(
                        nftTransfer.senderAccountID(),
                        token,
                        nftTransfer.receiverAccountID(),
                        result,
                        tokenRelStore,
                        accountStore,
                        autoCreationTest);
            }
        }
        return result;
    }
}
