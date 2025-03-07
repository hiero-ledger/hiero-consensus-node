// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asNumericContractId;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.KeyValueWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenExpiryWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyWrapper;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public abstract class UpdateCommonDecoder {

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
    protected static final int TOKEN_ADDRESS = 0;
    protected static final int HEDERA_TOKEN = 1;

    protected static final int EXPIRY = 1;
    protected static final int TOKEN_KEYS = 1;

    protected static final int KEY_TYPE = 0;
    protected static final int KEY_VALUE = 1;
    protected static final int SERIAL_NUMBERS = 1;
    protected static final int METADATA = 2;

    protected static final int INHERIT_ACCOUNT_KEY = 0;
    protected static final int CONTRACT_ID = 1;
    protected static final int ED25519 = 2;
    protected static final int ECDSA_SECP_256K1 = 3;
    protected static final int DELEGATABLE_CONTRACT_ID = 4;

    private static boolean isKnownImmutable(@Nullable final Token token) {
        return token != null && IMMUTABILITY_SENTINEL_KEY.equals(token.adminKeyOrElse(IMMUTABILITY_SENTINEL_KEY));
    }

    protected TokenUpdateTransactionBody.Builder decodeTokenUpdate(
            @NonNull final Tuple call,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final HederaNativeOperations nativeOperation) {
        final var tokenId = ConversionUtils.asTokenId(call.get(TOKEN_ADDRESS));
        final var hederaToken = (Tuple) call.get(HEDERA_TOKEN);

        final var tokenName = (String) hederaToken.get(0);
        final var tokenSymbol = (String) hederaToken.get(1);
        final var tokenTreasury = addressIdConverter.convert(hederaToken.get(2));
        final var memo = (String) hederaToken.get(3);
        final List<TokenKeyWrapper> tokenKeys =
                decodeTokenKeys(hederaToken.get(7), addressIdConverter, nativeOperation);
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

    protected List<TokenKeyWrapper> decodeTokenKeys(
            @NonNull final Tuple[] tokenKeysTuples,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final HederaNativeOperations nativeOperation) {
        final List<TokenKeyWrapper> tokenKeys = new ArrayList<>(tokenKeysTuples.length);
        for (final var tokenKeyTuple : tokenKeysTuples) {
            final var keyType = ((BigInteger) tokenKeyTuple.get(KEY_TYPE)).intValue();
            final Tuple keyValueTuple = tokenKeyTuple.get(KEY_VALUE);
            final var inheritAccountKey = (Boolean) keyValueTuple.get(INHERIT_ACCOUNT_KEY);
            final byte[] ed25519 = keyValueTuple.get(ED25519);
            final byte[] ecdsaSecp256K1 = keyValueTuple.get(ECDSA_SECP_256K1);
            final var entityIdFactory = nativeOperation.entityIdFactory();
            final var contractId =
                    asNumericContractId(entityIdFactory, addressIdConverter.convert(keyValueTuple.get(CONTRACT_ID)));
            final var delegatableContractId = asNumericContractId(
                    entityIdFactory, addressIdConverter.convert(keyValueTuple.get(DELEGATABLE_CONTRACT_ID)));

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

    @Nullable
    public TransactionBody decodeTokenUpdateKeys(@NonNull final HtsCallAttempt attempt) {

        final var call = decodeCall(attempt);

        final var tokenId = ConversionUtils.asTokenId(call.get(TOKEN_ADDRESS));
        final var tokenKeys =
                decodeTokenKeys(call.get(TOKEN_KEYS), attempt.addressIdConverter(), attempt.nativeOperations());

        // Build the transaction body
        final var txnBodyBuilder = TokenUpdateTransactionBody.newBuilder();
        txnBodyBuilder.token(tokenId);
        addKeys(tokenKeys, txnBodyBuilder);

        try {
            return TransactionBody.newBuilder().tokenUpdate(txnBodyBuilder).build();
        } catch (final IllegalArgumentException ignore) {
            return null;
        }
    }

    protected abstract Tuple decodeCall(@NonNull final HtsCallAttempt attempt);

    protected void addKeys(final List<TokenKeyWrapper> tokenKeys, final TokenUpdateTransactionBody.Builder builder) {
        tokenKeys.forEach(tokenKeyWrapper -> {
            final var key = tokenKeyWrapper.key().asGrpc();
            if (key == Key.DEFAULT) {
                throw new IllegalArgumentException();
            }
            setUsedKeys(builder, tokenKeyWrapper, key);
        });
    }

    private void setUsedKeys(
            final TokenUpdateTransactionBody.Builder builder, final TokenKeyWrapper tokenKeyWrapper, final Key key) {
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

    protected TokenExpiryWrapper decodeTokenExpiry(
            @NonNull final Tuple expiryTuple, @NonNull final AddressIdConverter addressIdConverter) {
        final var second = (long) expiryTuple.get(0);
        final var autoRenewAccount = addressIdConverter.convert(expiryTuple.get(1));
        final var autoRenewPeriod =
                Duration.newBuilder().seconds(expiryTuple.get(2)).build();
        return new TokenExpiryWrapper(
                second, autoRenewAccount.accountNumOrElse(0L) == 0 ? null : autoRenewAccount, autoRenewPeriod);
    }
}
