// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.pauses;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asTokenId;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.token.TokenPauseTransactionBody;
import com.hedera.hapi.node.token.TokenUnpauseTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides help in decoding an {@link HtsCallAttempt} representing an pause or unpause call into
 * a synthetic {@link TransactionBody}.
 */
@Singleton
public class PausesDecoder {
    @Inject
    public PausesDecoder() {
        // Dagger2
    }

    /**
     * Decodes the given {@code attempt} into a {@link TransactionBody} for a pause function call.
     *
     * @param attempt the attempt to decode
     * @return a {@link TransactionBody}
     */
    public TransactionBody decodePause(@NonNull final HtsCallAttempt attempt) {
        final var call = PausesTranslator.PAUSE.decodeCall(attempt.inputBytes());
        return TransactionBody.newBuilder()
                .tokenPause(pause(attempt, call.get(0)))
                .build();
    }

    private TokenPauseTransactionBody pause(
            @NonNull final HtsCallAttempt attempt, @NonNull final Address tokenAddress) {
        return TokenPauseTransactionBody.newBuilder()
                .token(asTokenId(attempt.nativeOperations().entityIdFactory(), tokenAddress))
                .build();
    }

    /**
     * Decodes the given {@code attempt} into a {@link TransactionBody} for an unpause function call.
     *
     * @param attempt the attempt to decode
     * @return a {@link TransactionBody}
     */
    public TransactionBody decodeUnpause(@NonNull final HtsCallAttempt attempt) {
        final var call = PausesTranslator.UNPAUSE.decodeCall(attempt.inputBytes());
        return TransactionBody.newBuilder()
                .tokenUnpause(unpause(attempt, call.get(0)))
                .build();
    }

    private TokenUnpauseTransactionBody unpause(
            @NonNull final HtsCallAttempt attempt, @NonNull final Address tokenAddress) {
        return TokenUnpauseTransactionBody.newBuilder()
                .token(asTokenId(attempt.nativeOperations().entityIdFactory(), tokenAddress))
                .build();
    }
}
