// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_BURN_AMOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_BURN_METADATA;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REJECTED_BY_MINT_CONTROL_HOOK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TREASURY_MUST_OWN_BURNED_NFT;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hedera.node.app.service.token.impl.validators.TokenSupplyChangeOpsValidator.verifyTokenInstanceAmounts;
import static com.hedera.node.app.spi.workflows.DispatchOptions.hookDispatchForExecution;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.EMPTY_METADATA;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.hooks.HookDispatchTransactionBody;
import com.hedera.hapi.node.hooks.HookExecution;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.MintBurnHooksABI;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.service.token.impl.validators.TokenSupplyChangeOpsValidator;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.service.token.records.TokenBurnStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.HooksConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_BURN}.
 */
@Singleton
public final class TokenBurnHandler extends BaseTokenHandler implements TransactionHandler {
    @NonNull
    private final TokenSupplyChangeOpsValidator validator;

    /**
     * Default constructor for injection.
     * @param validator the {@link TokenSupplyChangeOpsValidator} to use
     */
    @Inject
    public TokenBurnHandler(@NonNull final TokenSupplyChangeOpsValidator validator) {
        this.validator = requireNonNull(validator);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenBurnOrThrow();
        verifyTokenInstanceAmounts(op.amount(), op.serialNumbers(), op.hasToken(), INVALID_TOKEN_BURN_AMOUNT);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().tokenBurnOrThrow();
        final var tokenId = op.tokenOrElse(TokenID.DEFAULT);
        final var tokenStore = context.createStore(ReadableTokenStore.class);
        final var tokenMetadata = tokenStore.getTokenMeta(tokenId);
        if (tokenMetadata == null) throw new PreCheckException(INVALID_TOKEN_ID);

        // Get the full token to check for mint_control_hook_id
        final var token = tokenStore.get(tokenId);
        final boolean hasMintControlHook = token != null && token.hasMintControlHookId();

        // we will fail in handle() if token has no supply key
        if (tokenMetadata.hasSupplyKey()) {
            if (hasMintControlHook) {
                // If token has mint control hook, supply key is optional
                context.optionalKey(tokenMetadata.supplyKey());
            } else {
                // Otherwise, supply key is required
                context.requireKey(tokenMetadata.supplyKey());
            }
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var storeFactory = context.storeFactory();
        final var accountStore = storeFactory.writableStore(WritableAccountStore.class);
        final var tokenStore = storeFactory.writableStore(WritableTokenStore.class);
        final var tokenRelStore = storeFactory.writableStore(WritableTokenRelationStore.class);
        final var nftStore = storeFactory.writableStore(WritableNftStore.class);
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);

        final var txn = context.body();
        final var op = txn.tokenBurnOrThrow();
        final var tokenId = op.token();
        final var fungibleBurnCount = op.amount();
        // Wrapping the serial nums this way de-duplicates the serial nums:
        final var nftSerialNums = new ArrayList<>(new LinkedHashSet<>(op.serialNumbers()));

        // Get token first to check for mint control hook
        final var token = TokenHandlerHelper.getIfUsable(tokenId, tokenStore);

        // Check if token has mint control hook
        final boolean hasMintControlHook = token.hasMintControlHookId();

        if (!hasMintControlHook) {
            // No hook: unchanged behavior - require supply key
            validateTrue(token.supplyKey() != null, TOKEN_HAS_NO_SUPPLY_KEY);
        }

        // Determine source account based on hook result (if hook exists)
        AccountID sourceAccountId = token.treasuryAccountId();

        if (hasMintControlHook) {
            // Consult the mint control hook
            final var hookResult = consultMintControlHook(context, token, op, nftSerialNums);

            // Route based on party
            if (hookResult.partyValue() == MintBurnHooksABI.Party.SENDER.value()) {
                // Burn from sender (payer)
                sourceAccountId = context.payer();
            } else {
                // Burn from treasury (default)
                sourceAccountId = token.treasuryAccountId();
            }
        }

        // Validate semantics (amount, metadata, etc.)
        validateTrue(fungibleBurnCount >= 0, INVALID_TOKEN_BURN_AMOUNT);
        validator.validateBurn(fungibleBurnCount, nftSerialNums, tokensConfig);

        // Get source relation
        final var sourceRel = TokenHandlerHelper.getIfUsable(sourceAccountId, tokenId, tokenRelStore);

        if (token.hasKycKey()) {
            validateTrue(sourceRel.kycGranted(), ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);
        }

        final TokenBurnStreamBuilder tokenBurnStreamBuilderRecord =
                context.savepointStack().getBaseBuilder(TokenBurnStreamBuilder.class);
        if (token.tokenType() == TokenType.FUNGIBLE_COMMON) {
            validateTrue(fungibleBurnCount >= 0, INVALID_TOKEN_BURN_AMOUNT);
            final var newTotalSupply = changeSupply(
                    token,
                    sourceRel,
                    -fungibleBurnCount,
                    INVALID_TOKEN_BURN_AMOUNT,
                    accountStore,
                    tokenStore,
                    tokenRelStore,
                    context.expiryValidator());
            tokenBurnStreamBuilderRecord.newTotalSupply(newTotalSupply);
        } else {
            validateTrue(!nftSerialNums.isEmpty(), INVALID_TOKEN_BURN_METADATA);

            // Load and validate the nfts
            for (final Long nftSerial : nftSerialNums) {
                final var nft = nftStore.get(tokenId, nftSerial);
                validateTrue(nft != null, INVALID_NFT_ID);

                final var nftOwner = nft.ownerId();
                // If burning from treasury, check treasury ownership; if from sender, check sender ownership
                if (hasMintControlHook && sourceAccountId.equals(context.payer())) {
                    // Burning from sender - validate sender owns the NFT
                    validateTrue(nftOwner != null && nftOwner.equals(sourceAccountId), TREASURY_MUST_OWN_BURNED_NFT);
                } else {
                    // Burning from treasury - validate treasury ownership (null owner means treasury)
                    validateTrue(treasuryOwnsNft(nftOwner), TREASURY_MUST_OWN_BURNED_NFT);
                }
            }

            // Update counts for accounts and token rels
            final var newTotalSupply = changeSupply(
                    token,
                    sourceRel,
                    -nftSerialNums.size(),
                    FAIL_INVALID,
                    accountStore,
                    tokenStore,
                    tokenRelStore,
                    context.expiryValidator());

            // Update source account's NFT count
            final var sourceAcct = accountStore.get(sourceAccountId);
            final var updatedSourceAcct = sourceAcct
                    .copyBuilder()
                    .numberOwnedNfts(sourceAcct.numberOwnedNfts() - nftSerialNums.size())
                    .build();
            accountStore.put(updatedSourceAcct);

            // Remove the nft objects
            nftSerialNums.forEach(serialNum -> nftStore.remove(tokenId, serialNum));
            tokenBurnStreamBuilderRecord.newTotalSupply(newTotalSupply);
        }
        tokenBurnStreamBuilderRecord.tokenType(token.tokenType());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var op = feeContext.body();
        final var meta = TOKEN_OPS_USAGE_UTILS.tokenBurnUsageFrom(CommonPbjConverters.fromPbj(op));
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(
                        meta.getSerialNumsCount() > 0
                                ? SubType.TOKEN_NON_FUNGIBLE_UNIQUE
                                : SubType.TOKEN_FUNGIBLE_COMMON)
                .addBytesPerTransaction(meta.getBpt())
                .addNetworkRamByteSeconds(meta.getTransferRecordDb() * USAGE_PROPERTIES.legacyReceiptStorageSecs())
                .calculate();
    }

    /**
     * Consults the mint control hook to determine if burning is allowed and from which party.
     *
     * @param context the handle context
     * @param token the token being burned
     * @param op the token burn operation
     * @param nftSerialNums the NFT serial numbers being burned (for NFTs)
     * @return the result from the hook indicating allowed status and party
     * @throws HandleException if the hook rejects the burn or fails
     */
    private MintBurnHooksABI.AllowResult consultMintControlHook(
            @NonNull final HandleContext context,
            @NonNull final Token token,
            @NonNull final com.hedera.hapi.node.token.TokenBurnTransactionBody op,
            @NonNull final List<Long> nftSerialNums) {
        requireNonNull(context);
        requireNonNull(token);
        requireNonNull(op);
        requireNonNull(nftSerialNums);

        // Determine if supply key is active
        final boolean supplyKeySigned;
        if (token.supplyKey() != null) {
            supplyKeySigned = context.keyVerifier().authorizingSimpleKeys().stream()
                    .anyMatch(key -> key.equals(token.supplyKey()));
        } else {
            supplyKeySigned = false;
        }

        // Compute amount
        final long amount;
        if (token.tokenType() == TokenType.FUNGIBLE_COMMON) {
            amount = op.amount();
        } else {
            // For NFTs, amount is the number of serials to burn
            amount = nftSerialNums.size();
        }

        // Encode calldata using MintBurnHooksABI
        final byte[] calldata = MintBurnHooksABI.encodeAllowBurnArgs(amount, supplyKeySigned);

        // Determine gas limit
        final long gasLimit;
        if (op.hasHookControlGasLimit()) {
            gasLimit = op.hookControlGasLimitOrThrow();
        } else {
            // Use default gas limit from config
            final var hooksConfig = context.configuration().getConfigData(HooksConfig.class);
            gasLimit = hooksConfig.lambdaIntrinsicGasCost();
        }

        // Build HookExecution
        final var hookEntityId =
                HookEntityId.newBuilder().tokenId(token.tokenId()).build();
        final var hookCall = HookCall.newBuilder()
                .hookId(token.mintControlHookIdOrThrow())
                .evmHookCall(EvmHookCall.newBuilder()
                        .data(Bytes.wrap(calldata))
                        .gasLimit(gasLimit)
                        .build())
                .build();
        final var hookExecution = HookExecution.newBuilder()
                .hookEntityId(hookEntityId)
                .call(hookCall)
                .build();

        // Dispatch hook execution and get result
        final var hookDispatch = HookDispatchTransactionBody.newBuilder()
                .execution(hookExecution)
                .build();
        final var streamBuilder = context.dispatch(hookDispatchForExecution(
                context.payer(),
                TransactionBody.newBuilder().hookDispatch(hookDispatch).build(),
                HookDispatchStreamBuilder.class,
                signedTx -> signedTx, // no customization needed
                EMPTY_METADATA));

        validateTrue(streamBuilder.status() == SUCCESS, REJECTED_BY_MINT_CONTROL_HOOK);

        // Decode result
        final var result = streamBuilder.getEvmCallResult();
        final var allowResult = MintBurnHooksABI.decodeAllowResult(result.toByteArray());

        // Validate result
        validateTrue(allowResult.allowed(), REJECTED_BY_MINT_CONTROL_HOOK);

        return allowResult;
    }

    private boolean treasuryOwnsNft(final AccountID ownerID) {
        return ownerID == null;
    }
}
