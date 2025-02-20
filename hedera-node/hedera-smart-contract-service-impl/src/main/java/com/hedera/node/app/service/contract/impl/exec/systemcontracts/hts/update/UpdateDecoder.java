// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asNumericContractId;
import static com.hedera.node.app.spi.key.KeyUtils.IMMUTABILITY_SENTINEL_KEY;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.common.primitives.Longs;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenUpdateNftsTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody.Builder;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.KeyValueWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenExpiryWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyWrapper;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UpdateDecoder {
    /**
     * A customizer that refines {@link com.hedera.hapi.node.base.ResponseCodeEnum#INVALID_ACCOUNT_ID} and
     * {@link com.hedera.hapi.node.base.ResponseCodeEnum#INVALID_SIGNATURE} response codes.
     */
    public static final DispatchForResponseCodeHtsCall.FailureCustomizer FAILURE_CUSTOMIZER =
            (body, code, enhancement) -> {
                if (code == INVALID_ACCOUNT_ID) {
                    final var op = body.tokenUpdateOrThrow();
                    if (op.hasTreasury()) {
                        final var accountStore = enhancement.nativeOperations().readableAccountStore();
                        final var maybeTreasury = accountStore.getAccountById(op.treasuryOrThrow());
                        if (maybeTreasury == null) {
                            return INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
                        }
                    }
                } else if (code == INVALID_SIGNATURE) {
                    final var op = body.tokenUpdateOrThrow();
                    final var tokenStore = enhancement.nativeOperations().readableTokenStore();
                    if (isKnownImmutable(tokenStore.get(op.tokenOrElse(TokenID.DEFAULT)))) {
                        return TOKEN_IS_IMMUTABLE;
                    }
                }
                return code;
            };
    // below values correspond to  tuples' indexes
    private static final int TOKEN_ADDRESS = 0;
    private static final int HEDERA_TOKEN = 1;

    private static final int EXPIRY = 1;
    private static final int TOKEN_KEYS = 1;

    private static final int KEY_TYPE = 0;
    private static final int KEY_VALUE = 1;
    private static final int SERIAL_NUMBERS = 1;
    private static final int METADATA = 2;

    private static final int INHERIT_ACCOUNT_KEY = 0;
    private static final int CONTRACT_ID = 1;
    private static final int ED25519 = 2;
    private static final int ECDSA_SECP_256K1 = 3;
    private static final int DELEGATABLE_CONTRACT_ID = 4;

    @Inject
    public UpdateDecoder() {}

    private static boolean isKnownImmutable(@Nullable final Token token) {
        return token != null && IMMUTABILITY_SENTINEL_KEY.equals(token.adminKeyOrElse(IMMUTABILITY_SENTINEL_KEY));
    }

    /**
     * Decodes a call to {@link UpdateTranslator#TOKEN_UPDATE_INFO_FUNCTION_V1} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt
     * @return the synthetic transaction body
     */
    public @Nullable TransactionBody decodeTokenUpdateV1(@NonNull final HtsCallAttempt attempt) {
        final var call = UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V1.decodeCall(
                attempt.input().toArrayUnsafe());
        final var decoded = decodeTokenUpdate(call, attempt.addressIdConverter());
        return TransactionBody.newBuilder().tokenUpdate(decoded).build();
    }

    /**
     * Decodes a call to {@link UpdateTranslator#TOKEN_UPDATE_INFO_FUNCTION_V2} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt
     * @return the synthetic transaction body
     */
    public @Nullable TransactionBody decodeTokenUpdateV2(@NonNull final HtsCallAttempt attempt) {
        final var call = UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V2.decodeCall(
                attempt.input().toArrayUnsafe());
        final var decoded = decodeTokenUpdate(call, attempt.addressIdConverter());
        return TransactionBody.newBuilder().tokenUpdate(decoded).build();
    }

    /**
     * Decodes a call to {@link UpdateTranslator#TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt
     * @return the synthetic transaction body
     */
    public TransactionBody decodeTokenUpdateWithMetadata(@NonNull final HtsCallAttempt attempt) {
        final var call = UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA.decodeCall(
                attempt.input().toArrayUnsafe());
        final var decoded = decodeUpdateWithMeta(call, attempt.addressIdConverter());
        return TransactionBody.newBuilder().tokenUpdate(decoded).build();
    }

    /**
     * Decodes a call to {@link UpdateTranslator#TOKEN_UPDATE_INFO_FUNCTION_V3} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt
     * @return the synthetic transaction body
     */
    public @Nullable TransactionBody decodeTokenUpdateV3(@NonNull final HtsCallAttempt attempt) {
        final var call = UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V3.decodeCall(
                attempt.input().toArrayUnsafe());
        final var decoded = decodeTokenUpdate(call, attempt.addressIdConverter());
        return TransactionBody.newBuilder().tokenUpdate(decoded).build();
    }

    /**
     * Decodes a call to {@link UpdateExpiryTranslator#UPDATE_TOKEN_EXPIRY_INFO_V1} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt
     * @return the synthetic transaction body
     */
    public TransactionBody decodeTokenUpdateExpiryV1(@NonNull final HtsCallAttempt attempt) {
        final var call = UpdateExpiryTranslator.UPDATE_TOKEN_EXPIRY_INFO_V1.decodeCall(
                attempt.input().toArrayUnsafe());
        return decodeTokenUpdateExpiry(call, attempt.addressIdConverter());
    }

    /**
     * Decodes a call to {@link UpdateExpiryTranslator#UPDATE_TOKEN_EXPIRY_INFO_V2} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt
     * @return the synthetic transaction body
     */
    public TransactionBody decodeTokenUpdateExpiryV2(@NonNull final HtsCallAttempt attempt) {
        final var call = UpdateExpiryTranslator.UPDATE_TOKEN_EXPIRY_INFO_V2.decodeCall(
                attempt.input().toArrayUnsafe());
        return decodeTokenUpdateExpiry(call, attempt.addressIdConverter());
    }

    private TokenUpdateTransactionBody.Builder decodeTokenUpdate(
            @NonNull final Tuple call, @NonNull final AddressIdConverter addressIdConverter) {
        final var tokenId = ConversionUtils.asTokenId(call.get(TOKEN_ADDRESS));
        final var hederaToken = (Tuple) call.get(HEDERA_TOKEN);

        final var tokenName = (String) hederaToken.get(0);
        final var tokenSymbol = (String) hederaToken.get(1);
        final var tokenTreasury = addressIdConverter.convert(hederaToken.get(2));
        final var memo = (String) hederaToken.get(3);
        final List<TokenKeyWrapper> tokenKeys = decodeTokenKeys(hederaToken.get(7), addressIdConverter);
        final var tokenExpiry = decodeTokenExpiry(hederaToken.get(8), addressIdConverter);

        // Build the transaction body
        final var txnBodyBuilder = TokenUpdateTransactionBody.newBuilder();
        txnBodyBuilder.token(tokenId);

        if (tokenName != null) {
            txnBodyBuilder.name(tokenName);
        }
        if (tokenSymbol != null) {
            txnBodyBuilder.symbol(tokenSymbol);
        }
        if (memo != null) {
            txnBodyBuilder.memo(memo);
        }

        txnBodyBuilder.treasury(tokenTreasury);

        if (tokenExpiry.second() != 0) {
            txnBodyBuilder.expiry(
                    Timestamp.newBuilder().seconds(tokenExpiry.second()).build());
        }
        if (tokenExpiry.autoRenewAccount() != null) {
            txnBodyBuilder.autoRenewAccount(tokenExpiry.autoRenewAccount());
        }
        if (tokenExpiry.autoRenewPeriod() != null
                && tokenExpiry.autoRenewPeriod().seconds() != 0) {
            txnBodyBuilder.autoRenewPeriod(tokenExpiry.autoRenewPeriod());
        }
        addKeys(tokenKeys, txnBodyBuilder);
        return txnBodyBuilder;
    }

    public TokenUpdateTransactionBody.Builder decodeUpdateWithMeta(
            @NonNull final Tuple call, @NonNull final AddressIdConverter addressIdConverter) {
        final var tokenUpdateTransactionBody = decodeTokenUpdate(call, addressIdConverter);
        final var hederaToken = (Tuple) call.get(HEDERA_TOKEN);
        final Bytes tokenMetadata = hederaToken.size() > 9 ? Bytes.wrap((byte[]) hederaToken.get(9)) : null;
        if (tokenMetadata != null && tokenMetadata.length() > 0) {
            tokenUpdateTransactionBody.metadata(tokenMetadata);
        }
        final List<TokenKeyWrapper> tokenKeys = decodeTokenKeys(hederaToken.get(7), addressIdConverter);
        addKeys(tokenKeys, tokenUpdateTransactionBody);
        addMetaKey(tokenKeys, tokenUpdateTransactionBody);
        return tokenUpdateTransactionBody;
    }

    @Nullable
    public TransactionBody decodeTokenUpdateKeys(@NonNull final HtsCallAttempt attempt) {
        final boolean metadataSupport =
                attempt.configuration().getConfigData(ContractsConfig.class).metadataKeyAndFieldEnabled();
        final var call = UpdateKeysTranslator.TOKEN_UPDATE_KEYS_FUNCTION.decodeCall(
                attempt.input().toArrayUnsafe());

        final var tokenId = ConversionUtils.asTokenId(call.get(TOKEN_ADDRESS));
        final var tokenKeys = decodeTokenKeys(call.get(TOKEN_KEYS), attempt.addressIdConverter());

        // Build the transaction body
        final var txnBodyBuilder = TokenUpdateTransactionBody.newBuilder();
        txnBodyBuilder.token(tokenId);
        addKeys(tokenKeys, txnBodyBuilder);
        if (metadataSupport) {
            addMetaKey(tokenKeys, txnBodyBuilder);
        }
        try {
            return TransactionBody.newBuilder().tokenUpdate(txnBodyBuilder).build();
        } catch (IllegalArgumentException ignore) {
            return null;
        }
    }

    public TransactionBody decodeUpdateNFTsMetadata(@NonNull final HtsCallAttempt attempt) {
        final var call = UpdateNFTsMetadataTranslator.UPDATE_NFTs_METADATA.decodeCall(
                attempt.input().toArrayUnsafe());

        final var tokenId = ConversionUtils.asTokenId(call.get(TOKEN_ADDRESS));
        final List<Long> serialNumbers = Longs.asList(call.get(SERIAL_NUMBERS));
        final byte[] metadata = call.get(METADATA);

        final var txnBodyBuilder = TokenUpdateNftsTransactionBody.newBuilder()
                .token(tokenId)
                .serialNumbers(serialNumbers)
                .metadata(Bytes.wrap(metadata));

        return TransactionBody.newBuilder().tokenUpdateNfts(txnBodyBuilder).build();
    }

    private void addKeys(final List<TokenKeyWrapper> tokenKeys, final TokenUpdateTransactionBody.Builder builder) {
        tokenKeys.forEach(tokenKeyWrapper -> {
            final var key = tokenKeyWrapper.key().asGrpc();
            if (key == Key.DEFAULT) {
                throw new IllegalArgumentException();
            }
            setUsedKeys(builder, tokenKeyWrapper, key);
        });
    }

    private void setUsedKeys(Builder builder, TokenKeyWrapper tokenKeyWrapper, Key key) {
        if (tokenKeyWrapper.isUsedForAdminKey()) {
            builder.adminKey(key);
        }
        if (tokenKeyWrapper.isUsedForKycKey()) {
            builder.kycKey(key);
        }
        if (tokenKeyWrapper.isUsedForFreezeKey()) {
            builder.freezeKey(key);
        }
        if (tokenKeyWrapper.isUsedForWipeKey()) {
            builder.wipeKey(key);
        }
        if (tokenKeyWrapper.isUsedForSupplyKey()) {
            builder.supplyKey(key);
        }
        if (tokenKeyWrapper.isUsedForFeeScheduleKey()) {
            builder.feeScheduleKey(key);
        }
        if (tokenKeyWrapper.isUsedForPauseKey()) {
            builder.pauseKey(key);
        }
    }

    private void addMetaKey(final List<TokenKeyWrapper> tokenKeys, final TokenUpdateTransactionBody.Builder builder) {
        tokenKeys.forEach(tokenKeyWrapper -> {
            final var key = tokenKeyWrapper.key().asGrpc();
            if (tokenKeyWrapper.isUsedForMetadataKey()) {
                builder.metadataKey(key);
            }
        });
    }

    private TransactionBody decodeTokenUpdateExpiry(
            @NonNull final Tuple call, @NonNull final AddressIdConverter addressIdConverter) {
        final var tokenId = (Address) call.get(TOKEN_ADDRESS);
        final var expiryTuple = (Tuple) call.get(EXPIRY);
        final var txnBodyBuilder = TokenUpdateTransactionBody.newBuilder();

        txnBodyBuilder.token(ConversionUtils.asTokenId(tokenId));
        final var tokenExpiry = decodeTokenExpiry(expiryTuple, addressIdConverter);

        if (tokenExpiry.second() != 0) {
            txnBodyBuilder.expiry(
                    Timestamp.newBuilder().seconds(tokenExpiry.second()).build());
        }
        if (tokenExpiry.autoRenewAccount() != null) {
            txnBodyBuilder.autoRenewAccount(tokenExpiry.autoRenewAccount());
        }
        if (tokenExpiry.autoRenewPeriod() != null
                && tokenExpiry.autoRenewPeriod().seconds() != 0) {
            txnBodyBuilder.autoRenewPeriod(tokenExpiry.autoRenewPeriod());
        }

        return TransactionBody.newBuilder().tokenUpdate(txnBodyBuilder).build();
    }

    private List<TokenKeyWrapper> decodeTokenKeys(
            @NonNull final Tuple[] tokenKeysTuples, @NonNull final AddressIdConverter addressIdConverter) {
        final List<TokenKeyWrapper> tokenKeys = new ArrayList<>(tokenKeysTuples.length);
        for (final var tokenKeyTuple : tokenKeysTuples) {
            final var keyType = ((BigInteger) tokenKeyTuple.get(KEY_TYPE)).intValue();
            final Tuple keyValueTuple = tokenKeyTuple.get(KEY_VALUE);
            final var inheritAccountKey = (Boolean) keyValueTuple.get(INHERIT_ACCOUNT_KEY);
            final byte[] ed25519 = keyValueTuple.get(ED25519);
            final byte[] ecdsaSecp256K1 = keyValueTuple.get(ECDSA_SECP_256K1);
            final var contractId = asNumericContractId(addressIdConverter.convert(keyValueTuple.get(CONTRACT_ID)));
            final var delegatableContractId =
                    asNumericContractId(addressIdConverter.convert(keyValueTuple.get(DELEGATABLE_CONTRACT_ID)));

            tokenKeys.add(new TokenKeyWrapper(
                    keyType,
                    new KeyValueWrapper(
                            inheritAccountKey,
                            contractId.contractNumOrThrow() != 0 ? contractId : null,
                            ed25519,
                            ecdsaSecp256K1,
                            delegatableContractId.contractNumOrThrow() != 0 ? delegatableContractId : null)));
        }

        return tokenKeys;
    }

    private TokenExpiryWrapper decodeTokenExpiry(
            @NonNull final Tuple expiryTuple, @NonNull final AddressIdConverter addressIdConverter) {
        final var second = (long) expiryTuple.get(0);
        final var autoRenewAccount = addressIdConverter.convert(expiryTuple.get(1));
        final var autoRenewPeriod =
                Duration.newBuilder().seconds(expiryTuple.get(2)).build();
        return new TokenExpiryWrapper(
                second, autoRenewAccount.accountNumOrElse(0L) == 0 ? null : autoRenewAccount, autoRenewPeriod);
    }
}
