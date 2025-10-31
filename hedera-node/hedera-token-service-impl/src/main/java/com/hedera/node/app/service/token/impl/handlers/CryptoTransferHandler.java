// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_CHARGING_EXCEEDED_MAX_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.SubType.CRYPTO_TRANSFER_WITH_HOOKS;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage.HOUR_TO_SECOND_MULTIPLIER;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage.LONG_ACCOUNT_AMOUNT_BYTES;
import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsage.LONG_BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.CommonUtils.clampedAdd;
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
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.CustomFeeAssessmentStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferExecutor;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookCallsFactory;
import com.hedera.node.app.service.token.impl.validators.CryptoTransferValidator;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.WarmupContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.HooksConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.hapi.fees.FeeModelRegistry;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;

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
     *
     * @param validator the validator to use to validate the transaction
     */
    @Inject
    public CryptoTransferHandler(
            @NonNull final CryptoTransferValidator validator,
            @NonNull final HookCallsFactory hookCallsFactory,
            @NonNull final EntityIdFactory entityIdFactory) {
        this(validator, true, hookCallsFactory, entityIdFactory);
    }

    /**
     * Constructor for injection with the option to enforce mono-service restrictions on auto-creation custom fee.
     *
     * @param validator the validator to use to validate the transaction
     * @param enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments whether to enforce mono-service restrictions
     */
    public CryptoTransferHandler(
            @NonNull final CryptoTransferValidator validator,
            final boolean enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments,
            @NonNull final HookCallsFactory hookCallsFactory,
            @NonNull final EntityIdFactory entityIdFactory) {
        super(validator, hookCallsFactory, entityIdFactory);
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
        final var accountsConfig = context.configuration().getConfigData(AccountsConfig.class);
        final var hooksConfig = context.configuration().getConfigData(HooksConfig.class);

        final var transactionCategory =
                context.savepointStack().getBaseBuilder(StreamBuilder.class).category();
        validator.validateSemantics(op, ledgerConfig, accountsConfig, hooksConfig, transactionCategory);

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
        } catch (HandleException ex) {
            final var status = ex.getStatus();
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

        final var hookInfo = getHookInfo(op);
        /* Get subType based on the above information */
        final var subType = getSubType(
                numNftOwnershipChanges,
                totalTokenTransfers,
                customFeeHbarTransfers,
                customFeeTokenTransfers,
                triedAndFailedToUseCustomFees,
                hookInfo.usesHooks());
        if (hookInfo.usesHooks()) {
            return feeContext
                    .feeCalculatorFactory()
                    .feeCalculator(subType)
                    .addVerificationsPerTransaction(Math.max(0, feeContext.numTxnSignatures() - 1))
                    .addStorageBytesSeconds(HOUR_TO_SECOND_MULTIPLIER)
                    .addGas(hookInfo.totalGasLimitOfHooks())
                    .calculate();
        }
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(subType)
                .addBytesPerTransaction(bpt)
                .addRamByteSeconds(rbs * USAGE_PROPERTIES.legacyReceiptStorageSecs())
                .calculate();
    }

    /**
     * Sums the gas limits offered by any EVM allowance hooks present on:
     * HBAR account transfers (pre-tx and pre+post), Fungible token account transfers (pre-tx and pre+post),
     * NFT transfers for sender and receiver (pre-tx and pre+post)
     * Each increment uses {@code clampedAdd} to avoid overflow.
     */
    public static HookInfo getHookInfo(final CryptoTransferTransactionBody op) {
        var hookInfo = HookInfo.NO_HOOKS;
        for (final var aa : op.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
            hookInfo = merge(hookInfo, getTotalHookGasIfAny(aa));
        }
        for (final var ttl : op.tokenTransfers()) {
            for (final var aa : ttl.transfers()) {
                hookInfo = merge(hookInfo, getTotalHookGasIfAny(aa));
            }
            for (final var nft : ttl.nftTransfers()) {
                hookInfo = merge(hookInfo, addNftHookGas(nft));
            }
        }
        return hookInfo;
    }

    /**
     * Adds gas from pre-tx and pre+post allowance hooks on an account transfer.
     */
    private static HookInfo getTotalHookGasIfAny(@NonNull final AccountAmount aa) {
        final var hasPreTxHook = aa.hasPreTxAllowanceHook();
        final var hasPrePostTxHook = aa.hasPrePostTxAllowanceHook();
        if (!hasPreTxHook && !hasPrePostTxHook) {
            return HookInfo.NO_HOOKS;
        }
        long gas = 0L;
        if (hasPreTxHook) {
            gas = clampedAdd(
                    gas, aa.preTxAllowanceHookOrThrow().evmHookCallOrThrow().gasLimit());
        }
        if (hasPrePostTxHook) {
            final long gasPerCall =
                    aa.prePostTxAllowanceHookOrThrow().evmHookCallOrThrow().gasLimit();
            gas = clampedAdd(clampedAdd(gas, gasPerCall), gasPerCall);
        }
        return new HookInfo(true, gas);
    }

    /**
     * Adds gas from sender/receiver allowance hooks (pre-tx and pre+post) on an NFT transfer.
     */
    private static HookInfo addNftHookGas(@NonNull final NftTransfer nft) {
        final var hasSenderPre = nft.hasPreTxSenderAllowanceHook();
        final var hasSenderPrePost = nft.hasPrePostTxSenderAllowanceHook();
        final var hasReceiverPre = nft.hasPreTxReceiverAllowanceHook();
        final var hasReceiverPrePost = nft.hasPrePostTxReceiverAllowanceHook();
        if (!(hasSenderPre || hasSenderPrePost || hasReceiverPre || hasReceiverPrePost)) {
            return HookInfo.NO_HOOKS;
        }
        long gas = 0L;
        if (hasSenderPre) {
            gas = clampedAdd(
                    gas,
                    nft.preTxSenderAllowanceHookOrThrow().evmHookCallOrThrow().gasLimit());
        }
        if (hasSenderPrePost) {
            final long gasPerCall = nft.prePostTxSenderAllowanceHookOrThrow()
                    .evmHookCallOrThrow()
                    .gasLimit();
            gas = clampedAdd(clampedAdd(gas, gasPerCall), gasPerCall);
        }
        if (hasReceiverPre) {
            gas = clampedAdd(
                    gas,
                    nft.preTxReceiverAllowanceHookOrThrow().evmHookCallOrThrow().gasLimit());
        }
        if (hasReceiverPrePost) {
            final long gasPerCall = nft.prePostTxReceiverAllowanceHookOrThrow()
                    .evmHookCallOrThrow()
                    .gasLimit();
            gas = clampedAdd(clampedAdd(gas, gasPerCall), gasPerCall);
        }
        return new HookInfo(true, gas);
    }

    /**
     * Get the subType based on the number of NFT ownership changes, number of fungible token transfers,
     * number of custom fee hbar transfers, number of custom fee token transfers and whether the transaction
     * tried and failed to use custom fees.
     *
     * @param numNftOwnershipChanges number of NFT ownership changes
     * @param numFungibleTokenTransfers number of fungible token transfers
     * @param customFeeHbarTransfers number of custom fee hbar transfers
     * @param customFeeTokenTransfers number of custom fee token transfers
     * @param triedAndFailedToUseCustomFees whether the transaction tried and failed while validating custom fees.
     * If the failure includes custom fee error codes, the fee charged should not
     * use SubType.DEFAULT.
     * @return the subType
     */
    private static SubType getSubType(
            final int numNftOwnershipChanges,
            final int numFungibleTokenTransfers,
            final int customFeeHbarTransfers,
            final int customFeeTokenTransfers,
            final boolean triedAndFailedToUseCustomFees,
            final boolean withHooks) {
        if (withHooks) {
            return CRYPTO_TRANSFER_WITH_HOOKS;
        }
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

    /**
     * Utility to merge two partial HookInfo results.
     */
    private static HookInfo merge(final HookInfo a, final HookInfo b) {
        return new HookInfo(
                a.usesHooks() || b.usesHooks(), clampedAdd(a.totalGasLimitOfHooks(), b.totalGasLimitOfHooks()));
    }

    /**
     * Summary of hook usage and total gas.
     */
    public record HookInfo(boolean usesHooks, long totalGasLimitOfHooks) {
        public static final HookInfo NO_HOOKS = new HookInfo(false, 0L);
    }

    private record TransferEstimate(
            long hbarTransfers,
            long standardFungibleTokens,
            long customFeeFungibleTokens,
            long standardNftTokens,
            long customFeeNftTokens,
            long nftSerialCount,
            long createdAccounts,
            long createdAutoAssociations) {}

    private static class TransferEstimationCache {
        private final Map<TokenID, Token> tokenCache = new HashMap<>();
        private final Map<AccountID, Account> accountCache = new HashMap<>();
        private final Map<Bytes, AccountID> aliasCache = new HashMap<>();
        private final Set<String> tokenRelationCache = new HashSet<>();
        private final ReadableTokenStore tokenStore;
        private final ReadableAccountStore accountStore;
        private final ReadableTokenRelationStore tokenRelStore;
        private final HederaConfig hederaConfig;

        TransferEstimationCache(
                @NonNull final ReadableTokenStore tokenStore,
                @NonNull final ReadableAccountStore accountStore,
                @NonNull final ReadableTokenRelationStore tokenRelStore,
                @NonNull final HederaConfig hederaConfig) {
            this.tokenStore = requireNonNull(tokenStore);
            this.accountStore = requireNonNull(accountStore);
            this.tokenRelStore = requireNonNull(tokenRelStore);
            this.hederaConfig = requireNonNull(hederaConfig);
        }

        Token getToken(@NonNull final TokenID tokenId) {
            return tokenCache.computeIfAbsent(tokenId, tokenStore::get);
        }

        Account getAccount(@NonNull final AccountID accountId) {
            return accountCache.computeIfAbsent(accountId, accountStore::getAliasedAccountById);
        }

        AccountID getAccountByAlias(@NonNull final Bytes alias) {
            return aliasCache.computeIfAbsent(
                    alias, a -> accountStore.getAccountIDByAlias(hederaConfig.shard(), hederaConfig.realm(), a));
        }

        boolean hasTokenRelation(@NonNull final AccountID accountId, @NonNull final TokenID tokenId) {
            final String key = accountId.accountNumOrElse(0L) + ":" + tokenId.tokenNum();
            if (tokenRelationCache.contains(key)) {
                return true;
            }
            final var account = getAccount(accountId);
            if (account == null) {
                return false;
            }
            final var tokenRel = tokenRelStore.get(account.accountIdOrThrow(), tokenId);
            if (tokenRel != null) {
                tokenRelationCache.add(key);
                return true;
            }
            return false;
        }
    }

    @NonNull
    @Override
    public FeeResult calculateFeeResult(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var model = FeeModelRegistry.lookupModel(HederaFunctionality.CRYPTO_TRANSFER);
        final var op = feeContext.body().cryptoTransfer();

        // Initialize cache for efficient lookups
        final var tokenStore = feeContext.readableStore(ReadableTokenStore.class);
        final var accountStore = feeContext.readableStore(ReadableAccountStore.class);
        final var tokenRelStore = feeContext.readableStore(ReadableTokenRelationStore.class);
        final var hederaConfig = feeContext.configuration().getConfigData(HederaConfig.class);
        final var entitiesConfig = feeContext.configuration().getConfigData(EntitiesConfig.class);

        final var estimationCache = new TransferEstimationCache(tokenStore, accountStore, tokenRelStore, hederaConfig);

        // Estimated crypto transfer of all transfers
        final var estimatedCryptoTransfer = estimateCryptoTransfer(op, estimationCache, entitiesConfig);

        // Build fee parameters
        final Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, (long) feeContext.numTxnSignatures());
        params.put(Extra.ACCOUNTS, estimatedCryptoTransfer.hbarTransfers);
        params.put(Extra.STANDARD_FUNGIBLE_TOKENS, estimatedCryptoTransfer.standardFungibleTokens);
        params.put(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, estimatedCryptoTransfer.customFeeFungibleTokens);
        params.put(Extra.STANDARD_NON_FUNGIBLE_TOKENS, estimatedCryptoTransfer.standardNftTokens);
        params.put(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, estimatedCryptoTransfer.customFeeNftTokens);
        params.put(Extra.NFT_SERIALS, estimatedCryptoTransfer.nftSerialCount);
        params.put(Extra.CREATED_AUTO_ASSOCIATIONS, estimatedCryptoTransfer.createdAutoAssociations);
        params.put(Extra.CREATED_ACCOUNTS, estimatedCryptoTransfer.createdAccounts);

        final FeeCalculator feeCalculator = feeContext.feeCalculatorFactory().feeCalculator(DEFAULT);
        return model.computeFee(params, feeCalculator.getSimpleFeesSchedule());
    }

    private static TransferEstimate estimateCryptoTransfer(
            @NonNull final CryptoTransferTransactionBody op,
            @NonNull final TransferEstimationCache cache,
            @NonNull final EntitiesConfig entitiesConfig) {

        long hbarTransfers = 0;
        long standardFungibleTokens = 0;
        long customFeeFungibleTokens = 0;
        long standardNftTokens = 0;
        long customFeeNftTokens = 0;
        long nftSerialCount = 0;
        long createdAccounts = 0;
        long createdAutoAssociations = 0;

        final var processedAliases = new HashSet<Bytes>();

        // estimate HBAR transfers (early exit if empty)
        final var hbarTransferList = op.transfersOrElse(TransferList.DEFAULT).accountAmounts();
        if (!hbarTransferList.isEmpty()) {
            for (final var aa : hbarTransferList) {
                hbarTransfers++;
                // Check for account creation via alias (only for receivers)
                if (aa.amount() > 0 && aa.accountID() != null) {
                    createdAccounts += checkAliasCreationCached(aa.accountID(), cache, processedAliases);
                }
            }
        }

        // estimate token transfers (early exit if empty)
        final var tokenTransfers = op.tokenTransfers();
        if (tokenTransfers.isEmpty()) {
            return new TransferEstimate(hbarTransfers, 0, 0, 0, 0, 0, createdAccounts, 0);
        }

        for (final var tokenTransfer : tokenTransfers) {
            final var tokenId = tokenTransfer.token();
            if (tokenId == null) continue;

            // Early exit if no transfers for this token
            final var fungibleTransfers = tokenTransfer.transfers();
            final var nftTransfers = tokenTransfer.nftTransfers();
            if (fungibleTransfers.isEmpty() && nftTransfers.isEmpty()) {
                continue;
            }

            final var token = cache.getToken(tokenId);
            final boolean hasCustomFees = token != null && !token.customFees().isEmpty();

            final var accountsInThisTransfer = new HashSet<AccountID>();

            // Process fungible token transfers
            if (!fungibleTransfers.isEmpty()) {
                if (hasCustomFees) {
                    customFeeFungibleTokens++;
                } else {
                    standardFungibleTokens++;
                }

                for (final var aa : fungibleTransfers) {
                    final var accountId = aa.accountID();
                    if (accountId == null) continue;

                    if (aa.amount() > 0) {
                        createdAccounts += checkAliasCreationCached(accountId, cache, processedAliases);
                    }

                    if (!accountsInThisTransfer.contains(accountId)) {
                        if (willCreateAutoAssociationCached(accountId, tokenId, cache, entitiesConfig)) {
                            createdAutoAssociations++;
                        }
                        accountsInThisTransfer.add(accountId);
                    }
                }
            }

            // Process NFT transfers
            if (!nftTransfers.isEmpty()) {
                if (hasCustomFees) {
                    customFeeNftTokens++;
                } else {
                    standardNftTokens++;
                }
                nftSerialCount += nftTransfers.size();

                for (final var nft : nftTransfers) {
                    final var receiverId = nft.receiverAccountID();
                    if (receiverId == null) continue;

                    // Check for account creation
                    createdAccounts += checkAliasCreationCached(receiverId, cache, processedAliases);

                    // Check for auto-association creation (deduplicated per token)
                    if (!accountsInThisTransfer.contains(receiverId)) {
                        if (willCreateAutoAssociationCached(receiverId, tokenId, cache, entitiesConfig)) {
                            createdAutoAssociations++;
                        }
                        accountsInThisTransfer.add(receiverId);
                    }
                }
            }
        }

        return new TransferEstimate(
                hbarTransfers,
                standardFungibleTokens,
                customFeeFungibleTokens,
                standardNftTokens,
                customFeeNftTokens,
                nftSerialCount,
                createdAccounts,
                createdAutoAssociations);
    }

    private static int checkAliasCreationCached(
            @NonNull final AccountID accountId,
            @NonNull final TransferEstimationCache cache,
            @NonNull final HashSet<Bytes> processedAliases) {

        // Check if this is an alias (not a regular account number)
        if (!accountId.hasAlias() || accountId.accountNumOrElse(0L) != 0L) {
            return 0;
        }
        final var alias = accountId.alias();
        if (processedAliases.contains(alias)) {
            return 0;
        }
        processedAliases.add(alias);
        final var existingAccount = cache.getAccountByAlias(alias);
        return (existingAccount == null) ? 1 : 0;
    }

    private static boolean willCreateAutoAssociationCached(
            @NonNull final AccountID accountId,
            @NonNull final TokenID tokenId,
            @NonNull final TransferEstimationCache cache,
            @NonNull final EntitiesConfig entitiesConfig) {

        // Get the account (using cache)
        final var account = cache.getAccount(accountId);
        if (account == null) {
            return false;
        }

        // Check if association already exists (using cache)
        if (cache.hasTokenRelation(accountId, tokenId)) {
            return false;
        }

        // Check if account has auto-association slots available
        final var maxAutoAssociations = account.maxAutoAssociations();
        if (maxAutoAssociations == 0) {
            return false;
        }

        final var unlimitedEnabled = entitiesConfig.unlimitedAutoAssociationsEnabled();
        if (unlimitedEnabled && maxAutoAssociations == -1) {
            return true;
        }

        return account.usedAutoAssociations() < maxAutoAssociations;
    }
}
