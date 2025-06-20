// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantrevokekyc;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asTokenId;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.token.TokenGrantKycTransactionBody;
import com.hedera.hapi.node.token.TokenRevokeKycTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides help in decoding an {@link HtsCallAttempt} representing a grantKyc or revokeKyc call into
 * a synthetic {@link TransactionBody}.
 */
@Singleton
public class GrantRevokeKycDecoder {

    /**
     * Default constructor for injection.
     */
    @Inject
    public GrantRevokeKycDecoder() {
        // Dagger2
    }

    /**
     * Decodes the given {@code attempt} into a {@link TransactionBody} for a grantKyc function call.
     *
     * @param attempt the attempt to decode
     * @return a {@link TransactionBody}
     */
    public TransactionBody decodeGrantKyc(@NonNull final HtsCallAttempt attempt) {
        final var call = GrantRevokeKycTranslator.GRANT_KYC.decodeCall(attempt.inputBytes());
        return TransactionBody.newBuilder()
                .tokenGrantKyc(grantKyc(call.get(0), call.get(1), attempt))
                .build();
    }

    private TokenGrantKycTransactionBody grantKyc(
            @NonNull final Address tokenAddress,
            @NonNull final Address accountAddress,
            @NonNull final HtsCallAttempt attempt) {
        return TokenGrantKycTransactionBody.newBuilder()
                .token(asTokenId(attempt.nativeOperations().entityIdFactory(), tokenAddress))
                .account(attempt.addressIdConverter().convert(accountAddress))
                .build();
    }

    /**
     * Decodes the given {@code attempt} into a {@link TransactionBody} for a revokeKyc function call.
     *
     * @param attempt the attempt to decode
     * @return a {@link TransactionBody}
     */
    public TransactionBody decodeRevokeKyc(@NonNull final HtsCallAttempt attempt) {
        final var call = GrantRevokeKycTranslator.REVOKE_KYC.decodeCall(attempt.inputBytes());
        return TransactionBody.newBuilder()
                .tokenRevokeKyc(revokeKyc(call.get(0), call.get(1), attempt))
                .build();
    }

    private TokenRevokeKycTransactionBody revokeKyc(
            @NonNull final Address tokenAddress,
            @NonNull final Address accountAddress,
            @NonNull final HtsCallAttempt attempt) {
        return TokenRevokeKycTransactionBody.newBuilder()
                .token(asTokenId(attempt.nativeOperations().entityIdFactory(), tokenAddress))
                .account(attempt.addressIdConverter().convert(accountAddress))
                .build();
    }
}
