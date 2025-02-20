// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DOES_NOT_OWN_WIPED_NFT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hedera.node.app.service.token.impl.handlers.transfer.NFTOwnersChangeStep.removeFromList;
import static com.hedera.node.app.service.token.impl.validators.TokenSupplyChangeOpsValidator.verifyTokenInstanceAmounts;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.service.token.impl.validators.TokenSupplyChangeOpsValidator;
import com.hedera.node.app.service.token.records.TokenAccountWipeStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_ACCOUNT_WIPE}.
 */
@Singleton
public final class TokenAccountWipeHandler implements TransactionHandler {
    @NonNull
    private final TokenSupplyChangeOpsValidator validator;

    /**
     * Default constructor for injection.
     * @param validator the {@link TokenSupplyChangeOpsValidator} to use
     */
    @Inject
    public TokenAccountWipeHandler(@NonNull final TokenSupplyChangeOpsValidator validator) {
        this.validator = validator;
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().tokenWipeOrThrow();
        final var tokenStore = context.createStore(ReadableTokenStore.class);
        final var tokenMeta = tokenStore.getTokenMeta(op.tokenOrElse(TokenID.DEFAULT));
        if (tokenMeta == null) throw new PreCheckException(INVALID_TOKEN_ID);
        if (tokenMeta.hasWipeKey()) {
            context.requireKey(tokenMeta.wipeKey());
        }
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenWipeOrThrow();

        // All the pure checks for burning a token must also be checked for wiping a token
        verifyTokenInstanceAmounts(op.amount(), op.serialNumbers(), op.hasToken(), INVALID_WIPING_AMOUNT);

        validateTruePreCheck(op.hasAccount(), INVALID_ACCOUNT_ID);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        // Set up the stores and helper objects needed
        final var storeFactory = context.storeFactory();
        final var accountStore = storeFactory.writableStore(WritableAccountStore.class);
        final var tokenStore = storeFactory.writableStore(WritableTokenStore.class);
        final var tokenRelStore = storeFactory.writableStore(WritableTokenRelationStore.class);
        final var nftStore = storeFactory.writableStore(WritableNftStore.class);
        final var expiryValidator = context.expiryValidator();
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);

        // Assign relevant variables
        final var txn = context.body();
        final var op = txn.tokenWipeOrThrow();
        final var accountId = op.account();
        final var tokenId = op.token();
        final var fungibleWipeCount = op.amount();
        // Wrapping the serial nums this way de-duplicates the serial nums:
        final var nftSerialNums = new ArrayList<>(new LinkedHashSet<>(op.serialNumbers()));

        // Validate the semantics of the transaction
        final var validated = validateSemantics(
                accountId,
                tokenId,
                fungibleWipeCount,
                nftSerialNums,
                accountStore,
                tokenStore,
                tokenRelStore,
                expiryValidator,
                tokensConfig);
        final var acct = validated.account();
        final var token = validated.token();
        final var unaliasedId = acct.accountIdOrThrow();

        final long newTotalSupply;
        final long newAccountBalance;
        if (token.tokenType() == TokenType.FUNGIBLE_COMMON) {
            // Validate that there is at least one fungible token to wipe
            validateTrue(fungibleWipeCount >= 0, INVALID_WIPING_AMOUNT);

            // Check that the new total supply will not be negative
            newTotalSupply = token.totalSupply() - fungibleWipeCount;
            validateTrue(newTotalSupply >= 0, INVALID_WIPING_AMOUNT);

            // Check that the new token balance will not be negative
            newAccountBalance = validated.accountTokenRel().balance() - fungibleWipeCount;
            validateTrue(newAccountBalance >= 0, INVALID_WIPING_AMOUNT);
        } else {
            // Check if nft serial numbers are zero
            validateFalse(nftSerialNums.isEmpty(), INVALID_WIPING_AMOUNT);
            // Check that the new total supply will not be negative
            newTotalSupply = token.totalSupply() - nftSerialNums.size();
            validateTrue(newTotalSupply >= 0, INVALID_WIPING_AMOUNT);

            // Load and validate the nfts
            for (final Long nftSerial : nftSerialNums) {
                final var nftId = NftID.newBuilder()
                        .serialNumber(nftSerial)
                        .tokenId(tokenId)
                        .build();
                final var nft = TokenHandlerHelper.getIfUsable(nftId, nftStore);

                final var nftOwner = nft.ownerId();
                validateTrue(Objects.equals(nftOwner, unaliasedId), ACCOUNT_DOES_NOT_OWN_WIPED_NFT);
            }

            // Check that the new token balance will not be negative
            newAccountBalance = validated.accountTokenRel().balance() - nftSerialNums.size();
            validateTrue(newAccountBalance >= 0, INVALID_WIPING_AMOUNT);

            // Remove the NFTs
            nftSerialNums.forEach(serialNum -> {
                if (!unaliasedId.equals(token.treasuryAccountId())) {
                    removeFromList(
                            NftID.newBuilder()
                                    .serialNumber(serialNum)
                                    .tokenId(tokenId)
                                    .build(),
                            nftStore,
                            acct,
                            accountStore);
                }
                nftStore.remove(tokenId, serialNum);
            });
        }

        final Account.Builder updatedAcctBuilder =
                requireNonNull(accountStore.getAccountById(unaliasedId)).copyBuilder();
        // Update the NFT count for the account
        updatedAcctBuilder.numberOwnedNfts(acct.numberOwnedNfts() - nftSerialNums.size());
        // Finally, record all the changes
        if (newAccountBalance == 0) {
            updatedAcctBuilder.numberPositiveBalances(Math.max(acct.numberPositiveBalances() - 1, 0));
        }
        accountStore.put(updatedAcctBuilder.build());
        tokenStore.put(token.copyBuilder().totalSupply(newTotalSupply).build());
        tokenRelStore.put(validated
                .accountTokenRel()
                .copyBuilder()
                .balance(newAccountBalance)
                .build());
        // Note: record(s) for this operation will be built in a token finalization method so that we keep track of all
        // changes for records
        final var baseBuilderRecord = context.savepointStack().getBaseBuilder(TokenAccountWipeStreamBuilder.class);
        // Set newTotalSupply in record
        baseBuilderRecord.newTotalSupply(newTotalSupply);
        baseBuilderRecord.tokenType(token.tokenType());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var op = feeContext.body();
        final var readableTokenStore = feeContext.readableStore(ReadableTokenStore.class);
        final var tokenType = Optional.ofNullable(
                        readableTokenStore.get(op.tokenWipeOrThrow().tokenOrElse(TokenID.DEFAULT)))
                .map(Token::tokenType)
                .orElse(TokenType.FUNGIBLE_COMMON);
        final var meta = TOKEN_OPS_USAGE_UTILS.tokenWipeUsageFrom(CommonPbjConverters.fromPbj(op));
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(
                        tokenType.equals(TokenType.FUNGIBLE_COMMON)
                                ? SubType.TOKEN_FUNGIBLE_COMMON
                                : SubType.TOKEN_NON_FUNGIBLE_UNIQUE)
                .addBytesPerTransaction(meta.getBpt())
                .addNetworkRamByteSeconds(meta.getTransferRecordDb() * USAGE_PROPERTIES.legacyReceiptStorageSecs())
                .calculate();
    }

    private ValidationResult validateSemantics(
            @NonNull final AccountID accountId,
            @NonNull final TokenID tokenId,
            final long fungibleWipeCount,
            @NonNull final List<Long> nftSerialNums,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final ReadableTokenRelationStore tokenRelStore,
            @NonNull final ExpiryValidator expiryValidator,
            @NonNull final TokensConfig tokensConfig) {
        validateTrue(fungibleWipeCount > -1, INVALID_WIPING_AMOUNT);

        final var account = TokenHandlerHelper.getIfUsableForAliasedId(
                accountId, accountStore, expiryValidator, INVALID_ACCOUNT_ID);

        validator.validateWipe(fungibleWipeCount, nftSerialNums, tokensConfig);

        final var token = TokenHandlerHelper.getIfUsable(tokenId, tokenStore);
        validateTrue(token.wipeKey() != null, ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY);

        final var accountRel = TokenHandlerHelper.getIfUsable(account.accountIdOrThrow(), tokenId, tokenRelStore);
        if (token.hasKycKey()) {
            validateTrue(accountRel.kycGranted(), ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);
        }
        validateFalse(
                token.treasuryAccountId().equals(accountRel.accountId()),
                ResponseCodeEnum.CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT);

        return new ValidationResult(account, token, accountRel);
    }

    private record ValidationResult(
            @NonNull Account account, @NonNull Token token, @NonNull TokenRelation accountTokenRel) {}
}
