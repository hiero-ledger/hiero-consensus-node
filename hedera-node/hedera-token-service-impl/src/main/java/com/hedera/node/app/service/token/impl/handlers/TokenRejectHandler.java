// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_IS_IMMUTABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_TOKEN_REFERENCE_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_OWNER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_REFERENCE_REPEATED;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
import static com.hedera.node.app.service.token.impl.util.CryptoTransferHelper.createFungibleTransfer;
import static com.hedera.node.app.service.token.impl.util.CryptoTransferHelper.createNftTransfer;
import static com.hedera.node.app.service.token.impl.util.CryptoTransferHelper.nftTransfer;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsableForAliasedId;
import static com.hedera.node.app.spi.validation.Validations.validateAccountID;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.TokenReference;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.AdjustFungibleTokenChangesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.NFTOwnersChangeStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.base.utility.Pair;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#TOKEN_REJECT}.
 * This transaction type is used to reject tokens from an account and send them back to the treasury.
 */
@Singleton
public class TokenRejectHandler extends BaseTokenHandler implements TransactionHandler {

    @Inject
    public TokenRejectHandler() {
        // Exists for injection
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenRejectOrThrow();

        // If owner account is specified, verify that it's valid and require that it signs the transaction.
        // If not specified, payer account is considered as the one rejecting the tokens.
        if (op.hasOwner()) {
            final var accountStore = context.createStore(ReadableAccountStore.class);
            verifyOwnerAndRequireKey(op.ownerOrThrow(), context, accountStore);
        }
    }

    /**
     * Verifies that the owner account exists and ensures the account's key is valid and required for the transaction.
     *
     * @param ownerId The AccountID of the owner whose key needs to be validated.
     * @param context The PreHandleContext providing transaction context.
     * @param accountStore The store to access readable account information.
     * @throws PreCheckException If the sender's account is immutable or the sender's account ID is invalid.
     */
    private void verifyOwnerAndRequireKey(
            @NonNull final AccountID ownerId,
            @NonNull final PreHandleContext context,
            @NonNull final ReadableAccountStore accountStore)
            throws PreCheckException {

        final var ownerAccount = accountStore.getAliasedAccountById(ownerId);
        validateTruePreCheck(ownerAccount != null, INVALID_OWNER_ID);

        // If the sender account is immutable, then we throw an exception.
        final var key = ownerAccount.key();
        if (key == null || !isValid(key)) {
            throw new PreCheckException(ACCOUNT_IS_IMMUTABLE);
        }
        context.requireKey(key);
    }

    @SuppressWarnings("java:S2259")
    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn, "Transaction body cannot be null");
        final var op = txn.tokenRejectOrThrow();

        validateFalsePreCheck(op.rejections().isEmpty(), EMPTY_TOKEN_REFERENCE_LIST);
        if (op.hasOwner()) {
            validateAccountID(op.owner(), null);
        }

        final var uniqueTokenReferences = new HashSet<TokenReference>();
        for (final var rejection : op.rejections()) {
            if (!uniqueTokenReferences.add(rejection)) {
                throw new PreCheckException(TOKEN_REFERENCE_REPEATED);
            }
            // Ensure one token type per single rejection reference.
            validateFalsePreCheck(rejection.hasFungibleToken() && rejection.hasNft(), INVALID_TRANSACTION_BODY);

            if (rejection.hasFungibleToken()) {
                final var tokenID = rejection.fungibleToken();
                validateTruePreCheck(tokenID != null && !tokenID.equals(TokenID.DEFAULT), INVALID_TOKEN_ID);
            }
            if (rejection.hasNft()) {
                final var nftID = rejection.nft();
                validateTruePreCheck(nftID != null && nftID.tokenId() != null, INVALID_NFT_ID);
                validateTruePreCheck(nftID.serialNumber() > 0, INVALID_TOKEN_NFT_SERIAL_NUMBER);
            }
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().tokenRejectOrThrow();
        final var rejectingAccountID = op.ownerOrElse(context.payer());
        final var rejections = op.rejections();

        final var ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        validateTrue(tokensConfig.tokenRejectEnabled(), NOT_SUPPORTED);
        validateTrue(rejections.size() <= ledgerConfig.tokenRejectsMaxLen(), TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED);

        final var accountStore = context.storeFactory().writableStore(WritableAccountStore.class);
        final var rejectingAccount = getIfUsableForAliasedId(
                rejectingAccountID, accountStore, context.expiryValidator(), INVALID_ACCOUNT_ID);

        // Process the rejections to prepare for the transfers
        final var processedRejectTransfers = processRejectionsForTransfer(rejections, context, rejectingAccount);

        // Apply all changes to the handleContext's states by performing the transfer to the treasuries
        // Allowances will not be affected with the token reject
        final var transferContext = new TransferContextImpl(context);
        final var fungibleTokensStep = new AdjustFungibleTokenChangesStep(processedRejectTransfers, context.payer());
        final var nftOwnersChangeStep = new NFTOwnersChangeStep(processedRejectTransfers, context.payer());
        fungibleTokensStep.doIn(transferContext);
        nftOwnersChangeStep.doIn(transferContext);
    }

    /**
     * Processes the rejections specified in the transaction to prepare for the transfers to the treasuries.
     *
     * @param rejections The list of TokenReferences representing the rejections to process.
     * @param context The HandleContext providing the current handling context.
     * @param rejectingAccount The Account rejecting its tokens.
     * @return A List of TokenTransferLists representing the transfers for the rejected tokens to the treasuries
     */
    private List<TokenTransferList> processRejectionsForTransfer(
            @NonNull final List<TokenReference> rejections,
            @NonNull final HandleContext context,
            @NonNull final Account rejectingAccount) {

        final WritableTokenStore tokenStore = context.storeFactory().writableStore(WritableTokenStore.class);
        final WritableNftStore nftStore = context.storeFactory().writableStore(WritableNftStore.class);
        final WritableTokenRelationStore relStore =
                context.storeFactory().writableStore(WritableTokenRelationStore.class);

        final var accountID = rejectingAccount.accountIdOrThrow();

        // Validate fungible tokens and collect token transfer lists to the treasuries
        final var fungibleTransfers = rejections.stream()
                .filter(TokenReference::hasFungibleToken)
                .map(rejection -> processFungibleTokenRejection(rejection, rejectingAccount, tokenStore, relStore))
                .toList();

        // Validate NFTs and collect token transfer lists to the treasuries
        // NFTTransfers with same TokenID are grouped under the same TokenTransferList
        final var nftTransfers = rejections.stream()
                .filter(TokenReference::hasNft)
                .map(rejection -> processNFTTokenRejection(rejection.nftOrThrow(), accountID, nftStore, tokenStore))
                .collect(Collectors.groupingBy(Pair::left, Collectors.mapping(Pair::right, Collectors.toList())))
                .entrySet()
                .stream()
                .map(tokenIdNFTPair -> createNftTransfer(tokenIdNFTPair.getKey(), tokenIdNFTPair.getValue()))
                .toList();

        final var tokenTransferLists = new ArrayList<TokenTransferList>();
        tokenTransferLists.addAll(fungibleTransfers);
        tokenTransferLists.addAll(nftTransfers);
        return tokenTransferLists;
    }

    /**
     * Processes a single NFT rejection by performing validations and converting it to a Pair of TokenID & NftTransfer.
     *
     * @param nftID The NftID of the NFT being rejected.
     * @param rejectingAccountID The AccountID of the owner rejecting the NFT.
     * @param nftStore Access to writable NFT data.
     * @param tokenStore Access to writable token data.
     */
    private Pair<TokenID, NftTransfer> processNFTTokenRejection(
            @NonNull final NftID nftID,
            @NonNull final AccountID rejectingAccountID,
            @NonNull final WritableNftStore nftStore,
            @NonNull final WritableTokenStore tokenStore) {
        final var nft = getIfUsable(nftID, nftStore);
        final var token = getIfUsable(nftID.tokenIdOrThrow(), tokenStore);
        final var tokenTreasury = token.treasuryAccountIdOrThrow();
        validateTrue(!tokenTreasury.equals(rejectingAccountID), ACCOUNT_IS_TREASURY);
        validateTrue(nft.hasOwnerId() && rejectingAccountID.equals(nft.ownerId()), INVALID_OWNER_ID);

        return Pair.of(token.tokenId(), nftTransfer(rejectingAccountID, tokenTreasury, nftID.serialNumber()));
    }

    /**
     * Processes a single fungible token rejection by performing validations and builds the transfer for the rejected
     * token.
     *
     * @param rejection The token reference detailing the rejection.
     * @param rejectingAccount The Account rejecting the token.
     * @param tokenStore Access to writable token data.
     * @param relStore Access to writable token relations.
     */
    private TokenTransferList processFungibleTokenRejection(
            @NonNull final TokenReference rejection,
            @NonNull final Account rejectingAccount,
            @NonNull final WritableTokenStore tokenStore,
            @NonNull final WritableTokenRelationStore relStore) {
        final var accountID = rejectingAccount.accountIdOrThrow();
        final var token = getIfUsable(rejection.fungibleTokenOrThrow(), tokenStore);
        final var tokenId = token.tokenIdOrThrow();
        final var tokenRelation = getIfUsable(accountID, tokenId, relStore);
        validateTrue(token.treasuryAccountId() != null, INVALID_TREASURY_ACCOUNT_FOR_TOKEN);
        validateTrue(!token.treasuryAccountId().equals(accountID), ACCOUNT_IS_TREASURY);
        validateTrue(tokenRelation.balance() > 0, INSUFFICIENT_TOKEN_BALANCE);

        return createFungibleTransfer(tokenId, accountID, tokenRelation.balance(), token.treasuryAccountId());
    }

    @NonNull
    @Override
    public Fees calculateFees(final FeeContext feeContext) {
        final var body = feeContext.body();
        final var op = body.tokenRejectOrThrow();
        final var config = feeContext.configuration();
        final var tokenMultiplier =
                requireNonNull(config).getConfigData(FeesConfig.class).tokenTransferUsageMultiplier();

        final int totalRejections = op.rejections().size();
        int numOfFungibleTokenRejections = 0;
        int numOfNFTRejections = 0;
        for (final var rejection : op.rejections()) {
            if (rejection.hasFungibleToken()) {
                // Each fungible token rejection involves 2 AccountAmount transfers
                // We add 2 in order to match CryptoTransfer's bpt & rbs fee calculation
                numOfFungibleTokenRejections += 2;
            } else {
                numOfNFTRejections++;
            }
        }

        final int weightedTokensInvolved = tokenMultiplier * totalRejections;
        final int weightedFungibleTokens = tokenMultiplier * numOfFungibleTokenRejections;
        final long bpt =
                calculateBytesPerTransaction(weightedTokensInvolved, weightedFungibleTokens, numOfNFTRejections);
        final long rbs = USAGE_PROPERTIES.legacyReceiptStorageSecs() * bpt;

        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(getSubType(numOfNFTRejections))
                .addBytesPerTransaction(bpt)
                .addRamByteSeconds(rbs)
                .calculate();
    }

    private SubType getSubType(final int numOfNFTRejections) {
        return numOfNFTRejections != 0 ? TOKEN_NON_FUNGIBLE_UNIQUE : TOKEN_FUNGIBLE_COMMON;
    }

    private long calculateBytesPerTransaction(
            final int weightedTokensInvolved, final int weightedFungibleTokens, final int numNFTRejections) {
        return TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(
                weightedTokensInvolved, weightedFungibleTokens, numNFTRejections);
    }
}
