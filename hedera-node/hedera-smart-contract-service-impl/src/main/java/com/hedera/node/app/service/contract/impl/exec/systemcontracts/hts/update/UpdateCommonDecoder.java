// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ACCOUNT_ID;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenExpiryWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenTransactionBodyWrapper;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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

    protected static final int SERIAL_NUMBERS = 1;
    protected static final int METADATA = 2;

    private static boolean isKnownImmutable(@Nullable final Token token) {
        return token != null && IMMUTABILITY_SENTINEL_KEY.equals(token.adminKeyOrElse(IMMUTABILITY_SENTINEL_KEY));
    }

    protected TokenTransactionBodyWrapper decodeTokenUpdate(
            @NonNull final Tuple call,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final HederaNativeOperations nativeOperation,
            final boolean withMetadata) {
        final var tokenId = ConversionUtils.asTokenId(nativeOperation.entityIdFactory(), call.get(TOKEN_ADDRESS));
        final var hederaToken = (Tuple) call.get(HEDERA_TOKEN);

        final var tokenName = (String) hederaToken.get(0);
        final var tokenSymbol = (String) hederaToken.get(1);
        final var tokenTreasury = addressIdConverter.convert(hederaToken.get(2));
        final var memo = (String) hederaToken.get(3);
        final List<TokenKeyWrapper> tokenKeys =
                TokenKeyUtils.decodeTokenKeys(hederaToken.get(7), addressIdConverter, nativeOperation);
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
        // A blank memo from a contract means "unchanged", so don't externalize it
        if (memo != null && !memo.isBlank()) {
            txnBodyBuilder.memo(memo);
        }
        // A zero treasury means "unchanged", so don't externalize it as an update to 0.0.0
        if (!ZERO_ACCOUNT_ID.equals(tokenTreasury)) {
            txnBodyBuilder.treasury(tokenTreasury);
        }

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

        try {
            final var preemptingStatus = TokenKeyUtils.addTokenKeys(tokenKeys, withMetadata, txnBodyBuilder);
            if (withMetadata) {
                final Bytes tokenMetadata = hederaToken.size() > 9 ? Bytes.wrap((byte[]) hederaToken.get(9)) : null;
                if (tokenMetadata != null && tokenMetadata.length() > 0) {
                    txnBodyBuilder.metadata(tokenMetadata);
                }
            }
            return new TokenTransactionBodyWrapper(
                    TransactionBody.newBuilder().tokenUpdate(txnBodyBuilder).build(), preemptingStatus);
        } catch (IllegalArgumentException ignore) {
            return TokenTransactionBodyWrapper.EMPTY;
        }
    }

    protected abstract TokenTransactionBodyWrapper decodeTokenUpdateKeys(@NonNull final HtsCallAttempt attempt);

    protected TokenTransactionBodyWrapper decodeTokenUpdateKeys(
            @NonNull final HtsCallAttempt attempt, final boolean withMetadata) {

        final var call = decodeCall(attempt);

        final var tokenId =
                ConversionUtils.asTokenId(attempt.nativeOperations().entityIdFactory(), call.get(TOKEN_ADDRESS));
        final var tokenKeys = TokenKeyUtils.decodeTokenKeys(
                call.get(TOKEN_KEYS), attempt.addressIdConverter(), attempt.nativeOperations());

        // Build the transaction body
        try {
            final var txnBodyBuilder = TokenUpdateTransactionBody.newBuilder();
            txnBodyBuilder.token(tokenId);
            final var preemptingStatus = TokenKeyUtils.addTokenKeys(tokenKeys, withMetadata, txnBodyBuilder);
            return new TokenTransactionBodyWrapper(
                    TransactionBody.newBuilder().tokenUpdate(txnBodyBuilder).build(), preemptingStatus);
        } catch (final IllegalArgumentException ignore) {
            return TokenTransactionBodyWrapper.EMPTY;
        }
    }

    protected abstract Tuple decodeCall(@NonNull final HtsCallAttempt attempt);

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
