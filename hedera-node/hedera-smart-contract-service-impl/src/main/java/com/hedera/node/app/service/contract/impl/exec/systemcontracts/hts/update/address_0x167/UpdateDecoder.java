// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateCommonDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateExpiryTranslator;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UpdateDecoder extends UpdateCommonDecoder {

    /**
     * Default constructor for injection.
     */
    @Inject
    public UpdateDecoder() {
        // Dagger2
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
        final var decoded = decodeTokenUpdate(call, attempt.addressIdConverter(), attempt.nativeOperations());
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
        final var decoded = decodeTokenUpdate(call, attempt.addressIdConverter(), attempt.nativeOperations());
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
        final var decoded = decodeTokenUpdate(call, attempt.addressIdConverter(), attempt.nativeOperations());
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
        return decodeTokenUpdateExpiry(call, attempt);
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
        return decodeTokenUpdateExpiry(call, attempt);
    }

    @Override
    protected Tuple decodeCall(@NonNull final HtsCallAttempt attempt) {
        return UpdateKeysTranslator.TOKEN_UPDATE_KEYS_FUNCTION.decodeCall(
                attempt.input().toArrayUnsafe());
    }

    private TransactionBody decodeTokenUpdateExpiry(@NonNull final Tuple call, @NonNull final HtsCallAttempt attempt) {
        final var tokenId = (Address) call.get(TOKEN_ADDRESS);
        final var expiryTuple = (Tuple) call.get(EXPIRY);
        final var txnBodyBuilder = TokenUpdateTransactionBody.newBuilder();

        txnBodyBuilder.token(
                ConversionUtils.asTokenId(attempt.nativeOperations().entityIdFactory(), tokenId));
        final var tokenExpiry = decodeTokenExpiry(expiryTuple, attempt.addressIdConverter());

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
}
