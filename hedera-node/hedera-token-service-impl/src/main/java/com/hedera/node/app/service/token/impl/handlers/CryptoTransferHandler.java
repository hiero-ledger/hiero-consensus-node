// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_CHARGING_EXCEEDED_MAX_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage.LONG_ACCOUNT_AMOUNT_BYTES;
import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsage.LONG_BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.CustomFeeAssessmentStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferExecutor;
import com.hedera.node.app.service.token.impl.validators.CryptoTransferValidator;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.WarmupContext;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#CRYPTO_TRANSFER}.
 */
@Singleton
public class CryptoTransferHandler extends TransferExecutor implements TransactionHandler {
    private final CryptoTransferValidator validator;
    private final boolean enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments;

    /**
     * Default constructor for injection.
     * @param validator the validator to use to validate the transaction
     */
    @Inject
    public CryptoTransferHandler(@NonNull final CryptoTransferValidator validator) {
        this(validator, true);
    }

    /**
     * Constructor for injection with the option to enforce mono-service restrictions on auto-creation custom fee.
     * @param validator the validator to use to validate the transaction
     * @param enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments whether to enforce mono-service restrictions
     */
    public CryptoTransferHandler(
            @NonNull final CryptoTransferValidator validator,
            final boolean enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments) {
        super(validator);
        this.validator = validator;
        this.enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments =
                enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments;
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().cryptoTransferOrThrow();
        preHandle(context, op);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn);
        final var op = txn.cryptoTransfer();
        validateTruePreCheck(op != null, INVALID_TRANSACTION_BODY);
        validator.pureChecks(op);
    }

    @Override
    public void warm(@NonNull final WarmupContext context) {
        requireNonNull(context);

        final ReadableAccountStore accountStore = context.createStore(ReadableAccountStore.class);
        final ReadableTokenStore tokenStore = context.createStore(ReadableTokenStore.class);
        final ReadableNftStore nftStore = context.createStore(ReadableNftStore.class);
        final ReadableTokenRelationStore tokenRelationStore = context.createStore(ReadableTokenRelationStore.class);
        final CryptoTransferTransactionBody op = context.body().cryptoTransferOrThrow();

        // warm all accounts from the transfer list
        final TransferList transferList = op.transfersOrElse(TransferList.DEFAULT);
        transferList.accountAmounts().stream()
                .map(AccountAmount::accountID)
                .filter(Objects::nonNull)
                .forEach(accountStore::warm);

        // warm all token-data from the token transfer list
        final List<TokenTransferList> tokenTransfers = op.tokenTransfers();
        tokenTransfers.stream().filter(TokenTransferList::hasToken).forEach(tokenTransferList -> {
            final TokenID tokenID = tokenTransferList.tokenOrThrow();
            final Token token = tokenStore.get(tokenID);
            final AccountID treasuryID = token == null ? null : token.treasuryAccountId();
            if (treasuryID != null) {
                accountStore.warm(treasuryID);
            }
            for (final AccountAmount amount : tokenTransferList.transfers()) {
                amount.ifAccountID(accountID -> tokenRelationStore.warm(accountID, tokenID));
            }
            for (final NftTransfer nftTransfer : tokenTransferList.nftTransfers()) {
                warmNftTransfer(accountStore, tokenStore, nftStore, tokenRelationStore, tokenID, nftTransfer);
            }
        });
    }

    private void warmNftTransfer(
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final ReadableNftStore nftStore,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final TokenID tokenID,
            @NonNull final NftTransfer nftTransfer) {
        // warm sender
        nftTransfer.ifSenderAccountID(senderAccountID -> {
            final Account sender = accountStore.getAliasedAccountById(senderAccountID);
            if (sender != null) {
                sender.ifHeadNftId(nftStore::warm);
            }
            tokenRelationStore.warm(senderAccountID, tokenID);
        });

        // warm receiver
        nftTransfer.ifReceiverAccountID(receiverAccountID -> {
            final Account receiver = accountStore.getAliasedAccountById(receiverAccountID);
            if (receiver != null) {
                receiver.ifHeadTokenId(headTokenID -> {
                    tokenRelationStore.warm(receiverAccountID, headTokenID);
                    tokenStore.warm(headTokenID);
                });
                receiver.ifHeadNftId(nftStore::warm);
            }
            tokenRelationStore.warm(receiverAccountID, tokenID);
        });

        // warm neighboring NFTs
        final Nft nft = nftStore.get(tokenID, nftTransfer.serialNumber());
        if (nft != null) {
            nft.ifOwnerPreviousNftId(nftStore::warm);
            nft.ifOwnerNextNftId(nftStore::warm);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.cryptoTransferOrThrow();

        final var ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        final var accountsConfig = context.configuration().getConfigData(AccountsConfig.class);

        validator.validateSemantics(op, ledgerConfig, tokensConfig, accountsConfig);

        // create a new transfer context that is specific only for this transaction
        final var transferContext =
                new TransferContextImpl(context, enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments);
        final var recordBuilder = context.savepointStack().getBaseBuilder(CryptoTransferStreamBuilder.class);

        executeCryptoTransfer(txn, transferContext, context, recordBuilder);
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var body = feeContext.body();
        final var op = body.cryptoTransferOrThrow();
        final var config = feeContext.configuration();
        final var tokenMultiplier = config.getConfigData(FeesConfig.class).tokenTransferUsageMultiplier();

        /* BPT calculations shouldn't include any custom fee payment usage */
        int totalXfers =
                op.transfersOrElse(TransferList.DEFAULT).accountAmounts().size();

        var totalTokensInvolved = 0;
        var totalTokenTransfers = 0;
        var numNftOwnershipChanges = 0;
        for (final var tokenTransfers : op.tokenTransfers()) {
            totalTokensInvolved++;
            totalTokenTransfers += tokenTransfers.transfers().size();
            numNftOwnershipChanges += tokenTransfers.nftTransfers().size();
        }

        int weightedTokensInvolved = tokenMultiplier * totalTokensInvolved;
        int weightedTokenXfers = tokenMultiplier * totalTokenTransfers;
        final var bpt = weightedTokensInvolved * LONG_BASIC_ENTITY_ID_SIZE
                + (weightedTokenXfers + totalXfers) * LONG_ACCOUNT_AMOUNT_BYTES
                + TOKEN_ENTITY_SIZES.bytesUsedForUniqueTokenTransfers(numNftOwnershipChanges);

        /* Include custom fee payment usage in RBS calculations */
        var customFeeHbarTransfers = 0;
        var customFeeTokenTransfers = 0;
        final var involvedTokens = new HashSet<TokenID>();
        final var customFeeAssessor = new CustomFeeAssessmentStep(op);
        List<AssessedCustomFee> assessedCustomFees;
        boolean triedAndFailedToUseCustomFees = false;
        try {
            assessedCustomFees = customFeeAssessor.assessNumberOfCustomFees(feeContext);
        } catch (HandleException ignore) {
            final var status = ignore.getStatus();
            // If the transaction tried and failed to use custom fees, enable this flag.
            // This is used to charge a different canonical fees.
            triedAndFailedToUseCustomFees = status == INSUFFICIENT_PAYER_BALANCE_FOR_CUSTOM_FEE
                    || status == INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE
                    || status == CUSTOM_FEE_CHARGING_EXCEEDED_MAX_ACCOUNT_AMOUNTS;
            assessedCustomFees = new ArrayList<>();
        }
        for (final var fee : assessedCustomFees) {
            if (!fee.hasTokenId()) {
                customFeeHbarTransfers++;
            } else {
                customFeeTokenTransfers++;
                involvedTokens.add(fee.tokenId());
            }
        }
        totalXfers += customFeeHbarTransfers;
        weightedTokenXfers += tokenMultiplier * customFeeTokenTransfers;
        weightedTokensInvolved += tokenMultiplier * involvedTokens.size();
        long rbs = (totalXfers * LONG_ACCOUNT_AMOUNT_BYTES)
                + TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(
                        weightedTokensInvolved, weightedTokenXfers, numNftOwnershipChanges);

        /* Get subType based on the above information */
        final var subType = getSubType(
                numNftOwnershipChanges,
                totalTokenTransfers,
                customFeeHbarTransfers,
                customFeeTokenTransfers,
                triedAndFailedToUseCustomFees);
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(subType)
                .addBytesPerTransaction(bpt)
                .addRamByteSeconds(rbs * USAGE_PROPERTIES.legacyReceiptStorageSecs())
                .calculate();
    }

    /**
     * Get the subType based on the number of NFT ownership changes, number of fungible token transfers,
     * number of custom fee hbar transfers, number of custom fee token transfers and whether the transaction
     * tried and failed to use custom fees.
     * @param numNftOwnershipChanges number of NFT ownership changes
     * @param numFungibleTokenTransfers number of fungible token transfers
     * @param customFeeHbarTransfers number of custom fee hbar transfers
     * @param customFeeTokenTransfers number of custom fee token transfers
     * @param triedAndFailedToUseCustomFees whether the transaction tried and failed while validating custom fees.
     *                                      If the failure includes custom fee error codes, the fee charged should not
     *                                      use SubType.DEFAULT.
     * @return the subType
     */
    private static SubType getSubType(
            final int numNftOwnershipChanges,
            final int numFungibleTokenTransfers,
            final int customFeeHbarTransfers,
            final int customFeeTokenTransfers,
            final boolean triedAndFailedToUseCustomFees) {
        if (triedAndFailedToUseCustomFees) {
            return TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
        }
        if (numNftOwnershipChanges != 0) {
            if (customFeeHbarTransfers > 0 || customFeeTokenTransfers > 0) {
                return TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
            }
            return TOKEN_NON_FUNGIBLE_UNIQUE;
        }
        if (numFungibleTokenTransfers != 0) {
            if (customFeeHbarTransfers > 0 || customFeeTokenTransfers > 0) {
                return TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
            }
            return TOKEN_FUNGIBLE_COMMON;
        }
        return DEFAULT;
    }
}
