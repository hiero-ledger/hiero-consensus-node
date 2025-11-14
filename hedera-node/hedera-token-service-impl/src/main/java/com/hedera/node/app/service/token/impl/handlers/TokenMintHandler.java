// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_MINT_AMOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_MINT_METADATA;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_NFTS_IN_PRICE_REGIME_HAVE_BEEN_MINTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SERIAL_NUMBER_LIMIT_REACHED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.MAX_SERIAL_NO_ALLOWED;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.THREE_MONTHS_IN_SECONDS;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.service.token.impl.validators.TokenSupplyChangeOpsValidator;
import com.hedera.node.app.service.token.records.TokenMintStreamBuilder;
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
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_MINT}.
 */
@Singleton
public class TokenMintHandler extends BaseTokenHandler implements TransactionHandler {
    private final TokenSupplyChangeOpsValidator validator;

    /**
     * Default constructor for injection.
     * @param validator the token supply change ops validator
     */
    @Inject
    public TokenMintHandler(@NonNull final TokenSupplyChangeOpsValidator validator) {
        this.validator = requireNonNull(validator);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenMintOrThrow();
        final var tokenStore = context.createStore(ReadableTokenStore.class);
        final var tokenMeta = tokenStore.getTokenMeta(op.tokenOrElse(TokenID.DEFAULT));
        if (tokenMeta == null) throw new PreCheckException(INVALID_TOKEN_ID);
        if (tokenMeta.hasSupplyKey()) {
            context.requireKey(tokenMeta.supplyKey());
        }
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn);
        final var op = txn.tokenMintOrThrow();
        validateTruePreCheck(op.hasToken(), INVALID_TOKEN_ID);
        validateFalsePreCheck(!op.metadata().isEmpty() && op.amount() > 0, INVALID_TRANSACTION_BODY);
        validateFalsePreCheck(op.amount() < 0, INVALID_TOKEN_MINT_AMOUNT);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().tokenMintOrThrow();
        final var tokenId = context.body().tokenMintOrThrow().tokenOrThrow();

        validateSemantics(context);

        final var storeFactory = context.storeFactory();
        final var tokenStore = storeFactory.writableStore(WritableTokenStore.class);
        final var tokenRelStore = storeFactory.writableStore(WritableTokenRelationStore.class);
        final var accountStore = storeFactory.writableStore(WritableAccountStore.class);
        // validate token exists and is usable
        final var token = TokenHandlerHelper.getIfUsable(tokenId, tokenStore);
        validateTrue(token.supplyKey() != null, TOKEN_HAS_NO_SUPPLY_KEY);

        // validate treasury relation exists
        final var treasuryRel = TokenHandlerHelper.getIfUsable(token.treasuryAccountId(), tokenId, tokenRelStore);

        validateTrue(treasuryRel != null, INVALID_TREASURY_ACCOUNT_FOR_TOKEN);
        if (token.hasKycKey()) {
            validateTrue(treasuryRel.kycGranted(), ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);
        }

        final var recordBuilder = context.savepointStack().getBaseBuilder(TokenMintStreamBuilder.class);
        if (token.tokenType() == TokenType.FUNGIBLE_COMMON) {
            validateTrue(op.amount() >= 0, INVALID_TOKEN_MINT_AMOUNT);
            // we need to know if treasury mint while creation to ignore supply key exist or not.
            long newTotalSupply = mintFungible(
                    token,
                    treasuryRel,
                    op.amount(),
                    accountStore,
                    tokenStore,
                    tokenRelStore,
                    context.expiryValidator());
            recordBuilder.newTotalSupply(newTotalSupply);
        } else {
            // get the config needed for validation
            final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
            final var maxAllowedMints = tokensConfig.nftsMaxAllowedMints();
            final var nftStore = storeFactory.writableStore(WritableNftStore.class);
            // validate resources exist for minting nft
            final var meta = op.metadata();
            validateTrue(
                    nftStore.sizeOfState() + meta.size() <= maxAllowedMints, MAX_NFTS_IN_PRICE_REGIME_HAVE_BEEN_MINTED);
            // mint nft
            final var mintedSerials = mintNonFungible(
                    token,
                    treasuryRel,
                    meta,
                    context.consensusNow(),
                    accountStore,
                    tokenStore,
                    tokenRelStore,
                    nftStore,
                    context.expiryValidator());
            recordBuilder.newTotalSupply(tokenStore.get(tokenId).totalSupply());
            recordBuilder.serialNumbers(mintedSerials);
        }
        recordBuilder.tokenType(token.tokenType());
    }

    /**
     * Validates the semantics of the token mint transaction that involve state or config.
     * @param context - the handle context of the token mint transaction
     */
    private void validateSemantics(final HandleContext context) {
        requireNonNull(context);
        final var op = context.body().tokenMintOrThrow();
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        validator.validateMint(op.amount(), op.metadata(), tokensConfig);
    }

    /**
     * Minting nfts creates new instances of the given non-fungible token. Increments the
     * serial number of the given base unique token, and increments total owned nfts of the
     * non-fungible token.
     *
     * @param token           - the token to mint nfts for
     * @param treasuryRel     - the treasury relation of the token
     * @param metadata        - the metadata of the nft to be minted
     * @param consensusTime   - the consensus time of the transaction
     * @param accountStore    - the account store
     * @param tokenStore      - the token store
     * @param tokenRelStore   - the token relation store
     * @param nftStore        - the nft store
     * @param expiryValidator - the expiry validator
     */
    private List<Long> mintNonFungible(
            final Token token,
            @NonNull final TokenRelation treasuryRel,
            @NonNull final List<Bytes> metadata,
            @NonNull final Instant consensusTime,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final WritableTokenStore tokenStore,
            @NonNull final WritableTokenRelationStore tokenRelStore,
            @NonNull final WritableNftStore nftStore,
            @NonNull final ExpiryValidator expiryValidator) {
        final var metadataCount = metadata.size();
        validateFalse(metadata.isEmpty(), INVALID_TOKEN_MINT_METADATA);

        // validate token number from treasury relation
        final var tokenId = treasuryRel.tokenId();

        // get the treasury account
        var treasuryAccount = TokenHandlerHelper.getIfUsable(
                treasuryRel.accountIdOrThrow(), accountStore, expiryValidator, INVALID_TREASURY_ACCOUNT_FOR_TOKEN);

        // get the latest serial number minted for the token
        var currentSerialNumber = token.lastUsedSerialNumber();
        validateTrue((currentSerialNumber + metadataCount) <= MAX_SERIAL_NO_ALLOWED, SERIAL_NUMBER_LIMIT_REACHED);

        // Change the supply on token
        changeSupply(
                token,
                treasuryRel,
                metadataCount,
                FAIL_INVALID,
                accountStore,
                tokenStore,
                tokenRelStore,
                expiryValidator);
        // Since changeSupply call above modifies the treasuryAccount, we need to get the modified treasuryAccount
        treasuryAccount = accountStore.get(treasuryRel.accountIdOrThrow());
        // The token is modified in previous step, so we need to get the modified token
        final var modifiedToken = tokenStore.get(token.tokenId());
        final var mintedSerials = new ArrayList<Long>(metadata.size());

        // for each serial number minted increment serial numbers and create new unique token
        for (final var meta : metadata) {
            currentSerialNumber++;
            // The default sentinel account is used (0.0.0) to represent unique tokens owned by the treasury
            final var uniqueToken = buildNewlyMintedNft(consensusTime, tokenId, meta, currentSerialNumber);
            nftStore.putAndIncrementCount(uniqueToken);
            // all minted serials should be added to the receipt
            mintedSerials.add(currentSerialNumber);
        }
        // Update last used serial number and number of owned nfts and put the updated token and treasury
        // into the store
        final var copyToken = modifiedToken.copyBuilder();
        final var copyTreasury = treasuryAccount.copyBuilder();
        // Update Token and treasury
        copyToken.totalSupply(token.totalSupply() + metadataCount);
        copyToken.lastUsedSerialNumber(currentSerialNumber);
        copyTreasury.numberOwnedNfts(treasuryAccount.numberOwnedNfts() + metadataCount);

        tokenStore.put(copyToken.build());
        accountStore.put(copyTreasury.build());

        return mintedSerials;
    }

    /**
     * Builds a new unique token when minting a non-fungible token.
     * @param consensusTime - the consensus time of the transaction
     * @param tokenId - the token id
     * @param meta - the metadata of the nft
     * @param currentSerialNumber - the current serial number of the nft
     * @return - the newly built nft
     */
    @NonNull
    private Nft buildNewlyMintedNft(
            @NonNull final Instant consensusTime,
            @NonNull final TokenID tokenId,
            @NonNull final Bytes meta,
            final long currentSerialNumber) {
        return Nft.newBuilder()
                .nftId(NftID.newBuilder()
                        .tokenId(tokenId)
                        .serialNumber(currentSerialNumber)
                        .build())
                // ownerID is null to indicate owned by treasury
                .mintTime(Timestamp.newBuilder()
                        .seconds(consensusTime.getEpochSecond())
                        .nanos(consensusTime.getNano())
                        .build())
                .metadata(meta)
                .build();
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var op = feeContext.body().tokenMintOrThrow();
        final var subType = op.amount() > 0 ? SubType.TOKEN_FUNGIBLE_COMMON : SubType.TOKEN_NON_FUNGIBLE_UNIQUE;

        final var calculator = feeContext.feeCalculatorFactory().feeCalculator(subType);
        if (SubType.TOKEN_NON_FUNGIBLE_UNIQUE.equals(subType)) {
            calculator.resetUsage();
            // The price of nft mint should be increased based on number of signatures.
            // The first signature is free and is accounted in the base price, so we only need to add
            // the price of the rest of the signatures.
            calculator.addVerificationsPerTransaction(Math.max(0, feeContext.numTxnSignatures() - 1));
        }
        // FUTURE: lifetime parameter is not being used by the function below, in order to avoid making changes
        // to mono-service passed a default lifetime of 3 months here
        final var meta = TOKEN_OPS_USAGE_UTILS.tokenMintUsageFrom(
                CommonPbjConverters.fromPbj(feeContext.body()),
                CommonPbjConverters.fromPbj(subType),
                THREE_MONTHS_IN_SECONDS);

        calculator.addBytesPerTransaction(meta.getBpt());
        calculator.addRamByteSeconds(meta.getRbs());
        calculator.addNetworkRamByteSeconds(meta.getTransferRecordDb() * USAGE_PROPERTIES.legacyReceiptStorageSecs());
        return calculator.calculate();
    }
}
