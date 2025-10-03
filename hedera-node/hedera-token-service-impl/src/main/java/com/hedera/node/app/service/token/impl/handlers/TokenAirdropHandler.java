// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PENDING_NFT_AIRDROP_ALREADY_EXISTS;
import static com.hedera.hapi.util.HapiUtils.isHollow;
import static com.hedera.node.app.service.token.impl.handlers.transfer.AssociateTokenRecipientsStep.PLACEHOLDER_SYNTHETIC_ASSOCIATION;
import static com.hedera.node.app.service.token.impl.handlers.transfer.AssociateTokenRecipientsStep.associationFeeFor;
import static com.hedera.node.app.service.token.impl.util.AirdropHandlerHelper.createAccountPendingAirdrop;
import static com.hedera.node.app.service.token.impl.util.AirdropHandlerHelper.createFirstAccountPendingAirdrop;
import static com.hedera.node.app.service.token.impl.util.AirdropHandlerHelper.createFungibleTokenPendingAirdropId;
import static com.hedera.node.app.service.token.impl.util.AirdropHandlerHelper.createNftPendingAirdropId;
import static com.hedera.node.app.service.token.impl.util.AirdropHandlerHelper.createPendingAirdropRecord;
import static com.hedera.node.app.service.token.impl.util.AirdropHandlerHelper.separateFungibleTransfers;
import static com.hedera.node.app.service.token.impl.util.AirdropHandlerHelper.separateNftTransfers;
import static com.hedera.node.app.service.token.impl.util.CryptoTransferHelper.createAccountAmount;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.PendingAirdropValue;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAirdropStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAirdropStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferExecutor;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookCallFactory;
import com.hedera.node.app.service.token.impl.validators.CryptoTransferValidator;
import com.hedera.node.app.service.token.impl.validators.TokenAirdropValidator;
import com.hedera.node.app.service.token.records.TokenAirdropStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_AIRDROP}.
 */
@Singleton
public class TokenAirdropHandler extends TransferExecutor implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(TokenAirdropHandler.class);
    private final TokenAirdropValidator validator;

    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenAirdropHandler(
            @NonNull final TokenAirdropValidator validator,
            @NonNull final CryptoTransferValidator cryptoTransferValidator,
            @NonNull final HookCallFactory hookCallFactory) {
        super(cryptoTransferValidator, hookCallFactory);
        this.validator = validator;
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().tokenAirdropOrThrow();
        var convertedOp = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(op.tokenTransfers())
                .build();
        // Any receiver that has `receiverSigRequired` will be ignored during airdrops.
        // The airdrop will result in pending state or crypto transfer transaction depending on association and
        // signature.
        preHandleWithOptionalReceiverSignature(context, convertedOp);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn);
        final var op = txn.tokenAirdropOrThrow();
        validator.pureChecks(op);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenAirdropOrThrow();
        final var pendingStore = context.storeFactory().writableStore(WritableAirdropStore.class);
        final var accountStore = context.storeFactory().writableStore(WritableAccountStore.class);
        final var nftStore = context.storeFactory().readableStore(ReadableNftStore.class);
        final var tokenStore = context.storeFactory().readableStore(ReadableTokenStore.class);
        final var tokenRelStore = context.storeFactory().readableStore(ReadableTokenRelationStore.class);
        var recordBuilder = context.savepointStack().getBaseBuilder(TokenAirdropStreamBuilder.class);
        var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        List<TokenTransferList> tokenTransferList = new ArrayList<>();

        // validate the transaction body token transfers and NFT transfers
        validator.validateSemantics(context, op, accountStore, tokenStore, tokenRelStore, nftStore);

        // If the transaction is valid, charge custom fees in advance
        var convertedOp = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(op.tokenTransfers())
                .build();

        // after charging custom fees, the original transfer may be reduced (in case of fractional fee with
        // netOfTransfers = false)
        // so we should use the new transfer value instead of the original one
        var opBodyAfterCustomFeesAssessment = assessAndChargeCustomFee(context, convertedOp);
        for (final var xfers : opBodyAfterCustomFeesAssessment.tokenTransfers()) {
            throwIfReceiverCannotClaimAirdrop(xfers, accountStore);

            final var tokenId = xfers.tokenOrThrow();
            boolean shouldExecuteCryptoTransfer = false;
            final var transferListBuilder = TokenTransferList.newBuilder()
                    .expectedDecimals(xfers.expectedDecimals())
                    .token(tokenId);

            // process fungible token transfers if any.
            if (!xfers.transfers().isEmpty()) {
                // 1. separate transfers in to two lists
                // - one list for executing the transfer and one list for adding to pending state
                final var fungibleLists = separateFungibleTransfers(context, tokenId, xfers.transfers());
                validateTrue(
                        pendingStore.sizeOfState()
                                        + fungibleLists.pendingFungibleAmounts().size()
                                <= tokensConfig.maxAllowedPendingAirdrops(),
                        MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);
                // pureChecks validates there is only one debit, so findFirst should return one item
                final var senderAccountAmount = xfers.transfers().stream()
                        .filter(item -> item.amount() < 0)
                        .findFirst();
                final var senderId = senderAccountAmount.orElseThrow().accountIDOrThrow();
                // for FT, if airdrop is already in the pending state, we don't charge association fee again
                final var existingPendingAirdropsCount = countExistingPendingAirdrops(
                        senderId, fungibleLists.pendingFungibleAmounts(), tokenId, pendingStore);
                chargeAirdropFee(
                        context,
                        fungibleLists.pendingFungibleAmounts().size(),
                        fungibleLists.transfersNeedingAutoAssociation(),
                        existingPendingAirdropsCount);

                // 2. create and save pending airdrops in to state
                createPendingAirdropsForFungible(
                        fungibleLists.pendingFungibleAmounts(),
                        tokenId,
                        senderId,
                        accountStore,
                        pendingStore,
                        recordBuilder);

                // 3. create account amounts and add them to the transfer list
                if (!fungibleLists.transferFungibleAmounts().isEmpty()) {
                    shouldExecuteCryptoTransfer = true;
                    addTransfersToTransferList(
                            fungibleLists.transferFungibleAmounts(),
                            senderId,
                            senderAccountAmount.get().isApproval(),
                            transferListBuilder);
                }
            }

            // process non-fungible tokens transfers if any
            if (!xfers.nftTransfers().isEmpty()) {
                final var nftTransfer = xfers.nftTransfers().stream().findFirst();
                final var senderId = nftTransfer.orElseThrow().senderAccountIDOrThrow();
                // 2. separate NFT transfers in to two lists
                // - one list for executing the transfer and one list for adding to pending state
                final var nftLists = separateNftTransfers(context, tokenId, xfers.nftTransfers());
                validateTrue(
                        pendingStore.sizeOfState() + nftLists.pendingNftList().size()
                                <= tokensConfig.maxAllowedPendingAirdrops(),
                        MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);
                // there is no performant way to find if another serial of the same NFT is already in the pending state
                // so we always charge for association for NFTs
                chargeAirdropFee(
                        context, nftLists.pendingNftList().size(), nftLists.transfersNeedingAutoAssociation(), 0);

                // 3. create and save NFT pending airdrops in to state
                createPendingAirdropsForNFTs(
                        nftLists.pendingNftList(), tokenId, pendingStore, senderId, accountStore, recordBuilder);

                // 3. add to transfer list
                if (!nftLists.transferNftList().isEmpty()) {
                    shouldExecuteCryptoTransfer = true;
                    transferListBuilder.nftTransfers(nftLists.transferNftList());
                }
            }

            // build transfer list and add it to tokenTransferList
            if (shouldExecuteCryptoTransfer) {
                tokenTransferList.add(transferListBuilder.build());
            }
        }

        // transfer tokens, that are not in pending state, if any...
        if (!tokenTransferList.isEmpty()) {
            executeAirdropCryptoTransfer(context, tokenTransferList, recordBuilder);
        }
    }

    /**
     * Currently we are not supporting airdropping to a contract. If any of the receivers is a contract and
     * the contract doesn't have a key we throw an error
     */
    private static void throwIfReceiverCannotClaimAirdrop(
            @NonNull final TokenTransferList tokenTransferList, @NonNull final WritableAccountStore accountStore) {
        final var receiverIds = extractAllReceiverIds(tokenTransferList);
        for (final var receiverId : receiverIds) {
            final var account = accountStore.getAliasedAccountById(receiverId);
            // Missing accounts will be treated as auto-creation attempts
            if (account != null) {
                validateTrue(isHollow(account) || canClaimAirdrop(account.keyOrThrow()), NOT_SUPPORTED);
            }
        }
    }

    private static boolean canClaimAirdrop(@NonNull final Key key) {
        return switch (key.key().kind()) {
            case UNSET -> throw new IllegalStateException("Key kind cannot be UNSET");
            case CONTRACT_ID -> true;
            case ED25519 -> true;
            case RSA_3072 -> false;
            case ECDSA_384 -> false;
            case THRESHOLD_KEY ->
                key.thresholdKeyOrThrow().keysOrThrow().keys().stream()
                                .filter(TokenAirdropHandler::canClaimAirdrop)
                                .count()
                        >= key.thresholdKeyOrThrow().threshold();
            case KEY_LIST -> key.keyListOrThrow().keys().stream().allMatch(TokenAirdropHandler::canClaimAirdrop);
            case ECDSA_SECP256K1 -> true;
            case DELEGATABLE_CONTRACT_ID -> false;
        };
    }

    private static Collection<AccountID> extractAllReceiverIds(@NonNull final TokenTransferList tokenTransferList) {
        final var receivers = new HashSet<AccountID>();
        receivers.addAll(tokenTransferList.transfers().stream()
                .filter(t -> t.amount() > 0)
                .map(AccountAmount::accountID)
                .toList());
        receivers.addAll(tokenTransferList.nftTransfers().stream()
                .map(NftTransfer::receiverAccountID)
                .toList());
        return receivers;
    }

    /**
     * Charges the airdrop fee for the pending airdrops.The fee will be charged from the payer account and will vary
     * based on the number of pending airdrops created. This will be charged in handle method once we assess
     * number of pending airdrops created.No extra association fee is charged because the airdrop fee includes
     * token association fee.
     *
     * @param context             the {@link HandleContext} for the transaction
     * @param pendingAirdropsSize the number of pending airdrops created
     * @param numUnlimitedAssociationTransfers the number of unlimited association transfers
     * @param existingPendingAirdropsCount
     *  the number of pending airdrops that doesn't need to be charged for associations
     */
    private void chargeAirdropFee(
            final @NonNull HandleContext context,
            final int pendingAirdropsSize,
            final int numUnlimitedAssociationTransfers,
            final int existingPendingAirdropsCount) {
        // calculate fee, including association fee for new pending airdrops
        final var pendingAirdropFeeIncludingAssociationsFee =
                airdropFeeForPendingAirdrop(context) * (pendingAirdropsSize - existingPendingAirdropsCount);
        // calculate fee, without association fee for airdrops that already exist in the pending state
        // this is applicable only for fungible tokens
        final var pendingAirdropFeeWithoutAssociationsFee =
                airdropFeeForPendingAirdrop(context, false) * existingPendingAirdropsCount;
        final var airdropFeeForUnlimitedAssociations = airdropFee(context) * numUnlimitedAssociationTransfers;
        final var totalFee = pendingAirdropFeeIncludingAssociationsFee
                + airdropFeeForUnlimitedAssociations
                + pendingAirdropFeeWithoutAssociationsFee;
        // There are three cases for the fee charged in an airdrop transaction
        // 1. If there are no pending airdrops created and token is explicitly associated, only the CryptoTransferFee is
        // charged
        // 2. If there are no pending airdrops created but the token is not explicitly associated. But it has unlimited
        // max auto-associations set . Then we charge airdrop fee of $0.05 here and the triggered CryptoTransfer will
        // charge $0.05. So the total of $0.1
        // 3. If there are pending airdrops created, then we charge the airdrop fee of $0.1 per pending airdrop
        if (!context.tryToChargePayer(totalFee)) {
            throw new HandleException(INSUFFICIENT_PAYER_BALANCE);
        }
    }

    /**
     * Creates pending airdrops for all the assessed pending nft pending airdrops. This also adds
     * the pending airdrop created to the record.
     * @param nftLists the list of nft transfers
     * @param tokenId the token id
     * @param pendingStore the pending airdrop store
     * @param senderId the sender account id
     * @param accountStore the account store
     * @param recordBuilder the token airdrop record builder
     */
    private void createPendingAirdropsForNFTs(
            @NonNull final List<NftTransfer> nftLists,
            @NonNull final TokenID tokenId,
            @NonNull final WritableAirdropStore pendingStore,
            @NonNull final AccountID senderId,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final TokenAirdropStreamBuilder recordBuilder) {
        nftLists.forEach(item -> {
            // Each time it is important to get the latest sender account, as we are updating the account
            // with new pending airdrop
            final var senderAccount = requireNonNull(accountStore.getAliasedAccountById(senderId));
            final var receiverAccount =
                    requireNonNull(accountStore.getAliasedAccountById(item.receiverAccountIDOrThrow()));
            final var pendingId =
                    createNftPendingAirdropId(tokenId, item.serialNumber(), senderAccount, receiverAccount);
            // check for existence
            validateTrue(!pendingStore.exists(pendingId), PENDING_NFT_AIRDROP_ALREADY_EXISTS);
            updateNewPendingAirdrop(senderAccount, pendingId, null, accountStore, pendingStore);
            final var pendingAirdropRecord = createPendingAirdropRecord(pendingId, null);
            recordBuilder.addPendingAirdrop(pendingAirdropRecord);
        });
    }

    /**
     * Adds the transfers that are not in pending state to the transfer list. Constructs the debit for the sender and
     * the credits for the receivers.
     * @param fungibleAmounts the fungible airdrop amounts
     * @param sender the sender account id
     * @param isApproval is approval
     * @param transferListBuilder the transfer list builder
     */
    private void addTransfersToTransferList(
            @NonNull final List<AccountAmount> fungibleAmounts,
            @NonNull final AccountID sender,
            final boolean isApproval,
            @NonNull final TokenTransferList.Builder transferListBuilder) {
        List<AccountAmount> amounts = new LinkedList<>();
        final var receiversAmountList =
                fungibleAmounts.stream().filter(item -> item.amount() > 0).toList();
        var senderAmount =
                receiversAmountList.stream().mapToLong(AccountAmount::amount).sum();
        var newSenderAccountAmount = createAccountAmount(sender, -senderAmount, isApproval);
        amounts.add(newSenderAccountAmount);
        amounts.addAll(receiversAmountList);
        transferListBuilder.transfers(amounts);
    }

    /**
     * Creates pending airdrops from the assessed airdrop amounts.
     * @param fungibleAmounts the fungible airdrop amounts
     * @param tokenId the token id
     * @param senderId the sender account id
     * @param accountStore the account store
     * @param pendingStore the pending airdrop store
     * @param recordBuilder the token airdrop record builder
     */
    private void createPendingAirdropsForFungible(
            @NonNull final List<AccountAmount> fungibleAmounts,
            @NonNull final TokenID tokenId,
            @NonNull final AccountID senderId,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final WritableAirdropStore pendingStore,
            @NonNull final TokenAirdropStreamBuilder recordBuilder) {
        fungibleAmounts.forEach(accountAmount -> {
            // Each time it is important to get the latest sender account , as we are updating the account
            // with new pending airdrop
            final var senderAccount = requireNonNull(accountStore.getAliasedAccountById(senderId));
            final var receiverAccount =
                    requireNonNull(accountStore.getAliasedAccountById(accountAmount.accountIDOrThrow()));
            final var pendingId = createFungibleTokenPendingAirdropId(tokenId, senderAccount, receiverAccount);
            final var pendingValue = PendingAirdropValue.newBuilder()
                    .amount(accountAmount.amount())
                    .build();
            updateNewPendingAirdrop(senderAccount, pendingId, pendingValue, accountStore, pendingStore);
            // use the value from the store, in case we already have a pending airdrop with the same id
            final var pendingAirdropRecord = createPendingAirdropRecord(
                    pendingId, requireNonNull(pendingStore.get(pendingId)).pendingAirdropValue());
            recordBuilder.addPendingAirdrop(pendingAirdropRecord);
        });
    }

    /**
     * Assesses and charge the custom fee for the token airdrop transaction.
     *
     * @param context the handle context
     * @param body the crypto transfer transaction body
     * @return transfer transaction body after custom fees assessment
     * <p>
     * Note : In case of fractional fee with {@code netOfTransfers = false}, the original transfer
     * body is updated. A new account-amount is added to represent fractional part of the original
     * value that need to be transferred to the fee collector.
     * </p>
     */
    private CryptoTransferTransactionBody assessAndChargeCustomFee(
            @NonNull final HandleContext context, @NonNull final CryptoTransferTransactionBody body) {
        final var syntheticCryptoTransferTxn =
                TransactionBody.newBuilder().cryptoTransfer(body).build();
        final var transferContext = new TransferContextImpl(context, body, true);
        return chargeCustomFeeForAirdrops(syntheticCryptoTransferTxn, transferContext);
    }

    /**
     *  Create new {@link AccountPendingAirdrop} and if the sender has already existing pending airdrops
     *  link them together and update the account store and the pending airdrop store with the new values.
     */
    private void updateNewPendingAirdrop(
            @NonNull final Account senderAccount,
            @NonNull final PendingAirdropId pendingId,
            @Nullable final PendingAirdropValue pendingValue,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final WritableAirdropStore pendingStore) {
        if (pendingStore.contains(pendingId)) {
            // No need to update pointers to update the amount of fungible value
            update(pendingId, createFirstAccountPendingAirdrop(pendingValue), pendingStore);
        } else {
            final AccountPendingAirdrop newHeadAirdrop;
            if (senderAccount.hasHeadPendingAirdropId()) {
                // Get the previous head pending airdrop and update the previous airdrop ID
                final var currentHeadAirdropId = senderAccount.headPendingAirdropIdOrThrow();
                final var currentHeadAirdrop = pendingStore.get(currentHeadAirdropId);
                if (currentHeadAirdrop == null) {
                    log.error(
                            "Head pending airdrop {} not found for account {}",
                            currentHeadAirdropId,
                            senderAccount.accountId());
                    newHeadAirdrop = createFirstAccountPendingAirdrop(pendingValue);
                } else {
                    final var updatedHeadAirdrop = currentHeadAirdrop
                            .copyBuilder()
                            .previousAirdrop(pendingId)
                            .build();
                    // since we already validated the headAirdropId exists, we can safely update the store
                    pendingStore.put(currentHeadAirdropId, updatedHeadAirdrop);
                    // Create new account airdrop with next airdrop ID the previous head airdrop
                    newHeadAirdrop = createAccountPendingAirdrop(pendingValue, currentHeadAirdropId);
                }
            } else {
                newHeadAirdrop = createFirstAccountPendingAirdrop(pendingValue);
            }
            // Update the sender account with new head pending airdrop
            final var numPendingAirdrops = senderAccount.numberPendingAirdrops();
            final var updatedSenderAccount = senderAccount
                    .copyBuilder()
                    .headPendingAirdropId(pendingId)
                    .numberPendingAirdrops(numPendingAirdrops + 1)
                    .build();
            accountStore.put(updatedSenderAccount);
            pendingStore.putAndIncrementCount(pendingId, newHeadAirdrop);
        }
    }

    /**
     * Gets the airdrop fee for the token airdrop transaction when a pending airdrop is created. It will be
     * the sum of the association fee and the airdrop fee.
     * @param feeContext the fee context
     * @return the airdrop fee
     */
    private long airdropFeeForPendingAirdrop(@NonNull final HandleContext feeContext) {
        return airdropFeeForPendingAirdrop(feeContext, true);
    }

    /**
     * Gets the airdrop fee for the token airdrop transaction when a pending airdrop is created. It will be
     * the sum of the association fee and the airdrop fee.
     * @param feeContext the fee context
     * @param includeAssociationFee if association fee should be added
     * @return the airdrop fee
     */
    private long airdropFeeForPendingAirdrop(@NonNull final HandleContext feeContext, boolean includeAssociationFee) {
        var airdropFee = airdropFee(feeContext);
        if (includeAssociationFee) {
            final var associationFee = associationFeeFor(feeContext, PLACEHOLDER_SYNTHETIC_ASSOCIATION);
            airdropFee += associationFee;
        }
        return airdropFee;
    }

    /**
     * Gets the airdrop fee for the token airdrop transaction, when no pending airdrop is created.
     * This is charged when receiver is not yet associated with the token and has max auto-associations set to -1.
     * So, this will not create a pending airdrop and auto-association fee is automatically charged during the
     * CryptoTransfer auto-association step.
     * @param feeContext the fee context
     * @return the airdrop fee
     */
    private long airdropFee(final HandleContext feeContext) {
        return ((FeeContext) feeContext)
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .calculate()
                .totalFee();
    }

    /**
     *  Calculate the fees for the token airdrop transaction. Initially only the CryptoTransferFees is charged
     *  for the token airdrop transaction. The fees are charged based on number of pending airdrops that
     *  are created in the transaction. If there are no pending airdrops created, no extra fees is charged and
     *  only the CryptoTransferFees is charged.
     */
    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var op = feeContext.body().tokenAirdropOrThrow();
        final var tokensConfig = feeContext.configuration().getConfigData(TokensConfig.class);
        validateTrue(tokensConfig.airdropsEnabled(), NOT_SUPPORTED);
        // Always charge CryptoTransferFee as base price and then each time a pending airdrop is created
        // we charge airdrop fee in handle
        return calculateCryptoTransferFees(feeContext, op.tokenTransfers());
    }

    /**
     * Calculates the CryptoTransfer fees by dispatching a synthetic CryptoTransfer transaction.
     * @param feeContext the fee context
     * @param tokenTransfers the token transfers
     * @return the fees
     */
    private Fees calculateCryptoTransferFees(
            @NonNull FeeContext feeContext, @NonNull List<TokenTransferList> tokenTransfers) {
        final var cryptoTransferBody = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(tokenTransfers)
                .build();

        final var syntheticCryptoTransferTxn = TransactionBody.newBuilder()
                .cryptoTransfer(cryptoTransferBody)
                .transactionID(feeContext.body().transactionID())
                .build();

        return feeContext.dispatchComputeFees(syntheticCryptoTransferTxn, feeContext.payer());
    }

    private int countExistingPendingAirdrops(
            AccountID senderId,
            List<AccountAmount> fungibleAmounts,
            TokenID tokenId,
            ReadableAirdropStore pendingStore) {
        return toIntExact(fungibleAmounts.stream()
                .map(accountAmount ->
                        createFungibleTokenPendingAirdropId(tokenId, senderId, accountAmount.accountIDOrThrow()))
                .filter(pendingStore::exists)
                .count());
    }

    /**
     * Persists a new {@link PendingAirdropId} with given {@link AccountPendingAirdrop} into the state.
     * If there is existing airdrop with the same id we add the value to the existing drop.
     *
     * @param airdropId    - the airdropId to be persisted.
     * @param accountAirdrop - the account airdrop mapping for the given airdropId to be persisted.
     */
    public void update(
            @NonNull final PendingAirdropId airdropId,
            @NonNull final AccountPendingAirdrop accountAirdrop,
            @NonNull final WritableAirdropStore airdropState) {
        requireNonNull(airdropId);
        requireNonNull(accountAirdrop);
        requireNonNull(airdropState);

        if (airdropId.hasFungibleTokenType()) {
            final var existingAirdrop = requireNonNull(airdropState.get(airdropId));
            final var existingValue = existingAirdrop.pendingAirdropValue();
            long newValue;
            try {
                newValue = Math.addExact(
                        requireNonNull(accountAirdrop.pendingAirdropValue()).amount(),
                        requireNonNull(existingValue).amount());
            } catch (ArithmeticException e) {
                throw new HandleException(INSUFFICIENT_TOKEN_BALANCE);
            }
            final var newAccountAirdrop = existingAirdrop
                    .copyBuilder()
                    .pendingAirdropValue(
                            existingValue.copyBuilder().amount(newValue).build())
                    .build();
            airdropState.put(airdropId, newAccountAirdrop);
        }
    }
}
