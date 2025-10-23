// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.verifyNotEmptyKey;
import static com.hedera.node.app.service.token.impl.validators.CustomFeesValidator.SENTINEL_TOKEN_ID;
import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.service.token.impl.validators.CustomFeesValidator;
import com.hedera.node.app.service.token.impl.validators.TokenCreateValidator;
import com.hedera.node.app.service.token.records.TokenCreateStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_CREATE}.
 */
@Singleton
public class TokenCreateHandler extends BaseTokenHandler implements TransactionHandler {
    private final EntityIdFactory idFactory;
    private final CustomFeesValidator customFeesValidator;
    private final TokenCreateValidator tokenCreateValidator;

    /**
     * Default constructor for injection.
     * @param customFeesValidator custom fees validator
     * @param tokenCreateValidator token create validator
     */
    @Inject
    public TokenCreateHandler(
            @NonNull final EntityIdFactory idFactory,
            @NonNull final CustomFeesValidator customFeesValidator,
            @NonNull final TokenCreateValidator tokenCreateValidator) {
        this.idFactory = requireNonNull(idFactory);
        this.customFeesValidator = requireNonNull(customFeesValidator);
        this.tokenCreateValidator = requireNonNull(tokenCreateValidator);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();

        final var tokenCreateTxnBody = txn.tokenCreationOrThrow();
        if (tokenCreateTxnBody.hasTreasury()) {
            final var treasuryId = tokenCreateTxnBody.treasuryOrThrow();
            // Note: should throw INVALID_TREASURY_ACCOUNT_FOR_TOKEN after modularization
            context.requireKeyOrThrow(treasuryId, INVALID_ACCOUNT_ID);
        }
        if (tokenCreateTxnBody.hasAutoRenewAccount()) {
            final var autoRenewalAccountId = tokenCreateTxnBody.autoRenewAccountOrThrow();
            context.requireKeyOrThrow(autoRenewalAccountId, INVALID_AUTORENEW_ACCOUNT);
        }
        if (tokenCreateTxnBody.hasAdminKey()) {
            context.requireKey(tokenCreateTxnBody.adminKeyOrThrow());
        }
        final var customFees = tokenCreateTxnBody.customFees();
        addCustomFeeCollectorKeys(context, customFees);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        tokenCreateValidator.pureChecks(txn.tokenCreationOrThrow());
    }

    @Override
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenCreationOrThrow();
        // Create or get needed config and stores
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        final var storeFactory = context.storeFactory();
        final var accountStore = storeFactory.writableStore(WritableAccountStore.class);
        final var tokenStore = storeFactory.writableStore(WritableTokenStore.class);
        final var tokenRelationStore = storeFactory.writableStore(WritableTokenRelationStore.class);

        final var recordBuilder = context.savepointStack().getBaseBuilder(TokenCreateStreamBuilder.class);

        /* Validate if the current token can be created */
        validateTrue(
                tokenStore.sizeOfState() + 1 <= tokensConfig.maxNumber(),
                MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);

        // validate fields in the transaction body that involves checking with
        // dynamic properties or state.
        final var resolvedExpiryMeta = validateSemantics(context, accountStore, op, tokensConfig);

        // build a new token
        final var newTokenNum = context.entityNumGenerator().newEntityNum();
        final var newTokenId = idFactory.newTokenId(newTokenNum);
        final var newToken = buildToken(newTokenId, op, resolvedExpiryMeta);

        // validate custom fees and get back list of fees with created token denomination
        final var feesSetNeedingCollectorAutoAssociation = customFeesValidator.validateForCreation(
                newToken, accountStore, tokenRelationStore, tokenStore, op.customFees(), context.expiryValidator());
        // Put token into modifications map
        tokenStore.putAndIncrementCount(newToken);
        // associate token with treasury and collector ids of custom fees whose token denomination
        // is set to sentinel value
        associateAccounts(
                context,
                newToken,
                accountStore,
                tokenRelationStore,
                feesSetNeedingCollectorAutoAssociation,
                recordBuilder);

        // Since we have associated treasury and needed fee collector accounts in the previous step,
        // this relation must exist
        final var treasuryRel = requireNonNull(tokenRelationStore.get(op.treasuryOrThrow(), newTokenId));
        if (op.initialSupply() > 0) {
            // This keeps modified token with minted balance into modifications in token store
            mintFungible(
                    newToken,
                    treasuryRel,
                    op.initialSupply(),
                    accountStore,
                    tokenStore,
                    tokenRelationStore,
                    context.expiryValidator());
        }
        // Increment treasury's title count
        final var treasuryAccount = requireNonNull(accountStore.get(treasuryRel.accountIdOrThrow()));
        accountStore.put(treasuryAccount
                .copyBuilder()
                .numberTreasuryTitles(treasuryAccount.numberTreasuryTitles() + 1)
                .build());

        // Update record with newly created token id
        recordBuilder.tokenID(newTokenId);
        recordBuilder.tokenType(newToken.tokenType());
    }

    /**
     * Associate treasury account and the collector accounts of custom fees whose token denomination
     * is set to sentinel value, to use denomination as newly created token.
     *
     * @param newToken newly created token
     * @param accountStore account store
     * @param tokenRelStore token relation store
     * @param requireCollectorAutoAssociation set of custom fees whose token denomination is set to sentinel value
     * @param recordBuilder record builder
     */
    private void associateAccounts(
            final HandleContext context,
            final Token newToken,
            final WritableAccountStore accountStore,
            @NonNull final WritableTokenRelationStore tokenRelStore,
            final List<CustomFee> requireCollectorAutoAssociation,
            final TokenCreateStreamBuilder recordBuilder) {
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        final var entitiesConfig = context.configuration().getConfigData(EntitiesConfig.class);

        // This should exist as it is validated in validateSemantics
        final var treasury = accountStore.get(newToken.treasuryAccountId());
        // Validate if token relation can be created between treasury and new token
        // If this succeeds, create and link token relation.
        tokenCreateValidator.validateAssociation(entitiesConfig, tokensConfig, treasury, newToken, tokenRelStore);
        createAndLinkTokenRels(treasury, List.of(newToken), accountStore, tokenRelStore);
        recordBuilder.addAutomaticTokenAssociation(asTokenAssociation(newToken.tokenId(), treasury.accountId()));

        for (final var customFee : requireCollectorAutoAssociation) {
            // This should exist as it is validated in validateSemantics
            final var collector = accountStore.get(customFee.feeCollectorAccountIdOrThrow());
            if (treasury.accountId().equals(collector.accountId())) {
                continue;
            }

            // Ensure no duplicate relations are created
            final var existingTokenRel = tokenRelStore.get(collector.accountId(), newToken.tokenId());
            if (existingTokenRel != null) {
                continue;
            }

            // Validate if token relation can be created between collector and new token
            // If this succeeds, create and link token relation.
            tokenCreateValidator.validateAssociation(entitiesConfig, tokensConfig, collector, newToken, tokenRelStore);
            createAndLinkTokenRels(collector, List.of(newToken), accountStore, tokenRelStore);
            recordBuilder.addAutomaticTokenAssociation(asTokenAssociation(newToken.tokenId(), collector.accountId()));
        }
    }

    /**
     * Create a new token with the given parameters.
     *
     * @param newTokenId new token id
     * @param op token creation transaction body
     * @param resolvedExpiryMeta resolved expiry meta
     * @return newly created token
     */
    private Token buildToken(
            final TokenID newTokenId, final TokenCreateTransactionBody op, final ExpiryMeta resolvedExpiryMeta) {
        return new Token(
                newTokenId,
                op.name(),
                op.symbol(),
                op.decimals(),
                0, // is this correct ?
                op.treasury(),
                op.adminKey(),
                op.kycKey(),
                op.freezeKey(),
                op.wipeKey(),
                op.supplyKey(),
                op.feeScheduleKey(),
                op.pauseKey(),
                0,
                false,
                op.tokenType(),
                op.supplyType(),
                resolvedExpiryMeta.autoRenewAccountId(),
                // We want to return 0 instead of ExpiryMeta.NA when querying this token's info
                resolvedExpiryMeta.hasAutoRenewPeriod() ? resolvedExpiryMeta.autoRenewPeriod() : 0L,
                resolvedExpiryMeta.expiry(),
                op.memo(),
                op.maxSupply(),
                false,
                op.freezeDefault(),
                false,
                modifyCustomFeesWithSentinelValues(op.customFees(), newTokenId),
                op.metadata(),
                op.metadataKey());
    }

    /**
     * Modify the custom fees with the newly created token number as the token denomination.
     * For any custom fixed fees that has 0.0.0 as denominating tokenId, it should be changed
     * to the newly created token number before setting it to the token.
     *
     * @param customFees list of custom fees
     * @param newTokenId newly created token id
     * @return modified custom fees
     */
    private List<CustomFee> modifyCustomFeesWithSentinelValues(
            final List<CustomFee> customFees, final TokenID newTokenId) {
        return customFees.stream()
                .map(fee -> {
                    if (fee.hasFixedFee()
                            && fee.fixedFeeOrThrow().hasDenominatingTokenId()
                            && fee.fixedFeeOrThrow()
                                    .denominatingTokenIdOrThrow()
                                    .equals(SENTINEL_TOKEN_ID)) {
                        final var modifiedFixedFee = fee.fixedFeeOrThrow()
                                .copyBuilder()
                                .denominatingTokenId(newTokenId)
                                .build();
                        // Assign the modified fee back to the original fee object
                        return fee.copyBuilder().fixedFee(modifiedFixedFee).build();
                    }
                    return fee; // Return unmodified fee if conditions are not met
                })
                .toList();
    }

    /**
     * Get the expiry metadata for the token to be created from the transaction body.
     *
     * @param op token creation transaction body
     * @return given expiry metadata
     */
    private ExpiryMeta getExpiryMeta(@NonNull final TokenCreateTransactionBody op) {
        final var impliedExpiry = op.hasExpiry() ? op.expiry().seconds() : NA;

        return new ExpiryMeta(
                impliedExpiry,
                op.hasAutoRenewPeriod() ? op.autoRenewPeriod().seconds() : NA,
                // Shard and realm will be ignored if num is NA
                op.autoRenewAccount());
    }

    /**
     * Validate the semantics of the token creation transaction body, that involves checking with
     * dynamic properties or state.
     *
     * @param context handle context
     * @param accountStore account store
     * @param op token creation transaction body
     * @param config tokens configuration
     * @return resolved expiry metadata
     */
    private ExpiryMeta validateSemantics(
            @NonNull final HandleContext context,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final TokenCreateTransactionBody op,
            @NonNull final TokensConfig config) {
        requireNonNull(context);
        requireNonNull(accountStore);
        requireNonNull(op);
        requireNonNull(config);

        // validate different token create fields
        tokenCreateValidator.validate(context, accountStore, op, config);

        // validate expiration and auto-renew account if present
        final var givenExpiryMeta = getExpiryMeta(op);
        final var resolvedExpiryMeta = context.expiryValidator()
                .resolveCreationAttempt(false, givenExpiryMeta, HederaFunctionality.TOKEN_CREATE);

        // validate auto-renew account exists
        if (resolvedExpiryMeta.hasAutoRenewAccountId()) {
            TokenHandlerHelper.getIfUsableForAutoRenew(
                    resolvedExpiryMeta.autoRenewAccountId(),
                    accountStore,
                    context.expiryValidator(),
                    INVALID_AUTORENEW_ACCOUNT);
        }
        return resolvedExpiryMeta;
    }

    /* --------------- Helper methods --------------- */

    /**
     * Validates the collector key from the custom fees.
     *
     * @param context given context
     * @param customFeesList list with the custom fees
     */
    private void addCustomFeeCollectorKeys(
            @NonNull final PreHandleContext context, @NonNull final List<CustomFee> customFeesList)
            throws PreCheckException {

        for (final var customFee : customFeesList) {
            final var collector = customFee.feeCollectorAccountIdOrElse(AccountID.DEFAULT);

            // Verify that the collector either has a non-empty alias OR a mutable key
            final var acctStore = context.createStore(ReadableAccountStore.class);
            final var collectorAcct = acctStore.getAccountById(collector);
            if (collectorAcct != null
                    && (collectorAcct.alias() == null || Bytes.EMPTY.equals(collectorAcct.alias()))
                    && (collectorAcct.hasKey())) {
                verifyNotEmptyKey(collectorAcct.key(), INVALID_CUSTOM_FEE_COLLECTOR);
            }

            /* A fractional fee collector and a collector for a fixed fee denominated
            in the units of the newly created token both must always sign a TokenCreate,
            since these are automatically associated to the newly created token. */
            if (customFee.hasFixedFee()) {
                final var fixedFee = customFee.fixedFeeOrThrow();
                final var alwaysAdd = fixedFee.hasDenominatingTokenId()
                        && fixedFee.denominatingTokenIdOrThrow().tokenNum() == 0L;
                addAccount(context, collector, alwaysAdd);
            } else if (customFee.hasFractionalFee()) {
                context.requireKeyOrThrow(collector, INVALID_CUSTOM_FEE_COLLECTOR);
            } else if (customFee.hasRoyaltyFee()) {
                // TODO: Need to validate if this is actually needed
                final var royaltyFee = customFee.royaltyFeeOrThrow();
                var alwaysAdd = false;
                if (royaltyFee.hasFallbackFee()) {
                    final var fFee = royaltyFee.fallbackFeeOrThrow();
                    alwaysAdd = fFee.hasDenominatingTokenId()
                            && fFee.denominatingTokenIdOrThrow().tokenNum() == 0;
                }
                addAccount(context, collector, alwaysAdd);
            }
        }
    }

    /**
     * Signs the metadata or adds failure status.
     *
     * @param context given context
     * @param collector the ID of the collector
     * @param alwaysAdd if true, will always add the key
     */
    private void addAccount(
            @NonNull final PreHandleContext context, @NonNull final AccountID collector, final boolean alwaysAdd)
            throws PreCheckException {
        if (alwaysAdd) {
            context.requireKeyOrThrow(collector, INVALID_CUSTOM_FEE_COLLECTOR);
        } else {
            context.requireKeyIfReceiverSigRequired(collector, INVALID_CUSTOM_FEE_COLLECTOR);
        }
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var body = feeContext.body();
        final var meta = TOKEN_OPS_USAGE_UTILS.tokenCreateUsageFrom(CommonPbjConverters.fromPbj(body));
        final var op = body.tokenCreationOrThrow();
        final var type = op.tokenType();

        final long tokenSizes = TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(
                        meta.getNumTokens(), meta.getFungibleNumTransfers(), meta.getNftsTransfers())
                * USAGE_PROPERTIES.legacyReceiptStorageSecs();

        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(tokenSubTypeFrom(
                        type, op.hasFeeScheduleKey() || !op.customFees().isEmpty()))
                .addBytesPerTransaction(meta.getBaseSize())
                .addRamByteSeconds(tokenSizes)
                .addNetworkRamByteSeconds(meta.getNetworkRecordRb() * USAGE_PROPERTIES.legacyReceiptStorageSecs())
                .addRamByteSeconds((meta.getBaseSize() + meta.getCustomFeeScheduleSize()) * meta.getLifeTime())
                .calculate();
    }

    /**
     * Get the token subtype to be used for the fees calculation.
     * @param tokenType the token type
     * @param hasCustomFees if the token has custom fees
     * @return the token subtype
     */
    public static SubType tokenSubTypeFrom(final TokenType tokenType, boolean hasCustomFees) {
        return switch (tokenType) {
            case FUNGIBLE_COMMON ->
                hasCustomFees ? SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES : SubType.TOKEN_FUNGIBLE_COMMON;
            case NON_FUNGIBLE_UNIQUE ->
                hasCustomFees ? SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES : SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
        };
    }
}
