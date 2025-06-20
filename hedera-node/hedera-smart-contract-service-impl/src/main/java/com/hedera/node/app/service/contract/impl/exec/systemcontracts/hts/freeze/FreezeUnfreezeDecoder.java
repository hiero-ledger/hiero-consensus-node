// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.freeze;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.token.TokenFreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenUnfreezeAccountTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class FreezeUnfreezeDecoder {

    @Inject
    public FreezeUnfreezeDecoder() {
        // Dagger2
    }

    public TransactionBody decodeFreeze(@NonNull final HtsCallAttempt attempt) {
        final var call = FreezeUnfreezeTranslator.FREEZE.decodeCall(attempt.inputBytes());
        return TransactionBody.newBuilder()
                .tokenFreeze(freeze(attempt, call.get(0), call.get(1)))
                .build();
    }

    public TransactionBody decodeUnfreeze(@NonNull final HtsCallAttempt attempt) {
        final var call = FreezeUnfreezeTranslator.UNFREEZE.decodeCall(attempt.inputBytes());
        return TransactionBody.newBuilder()
                .tokenUnfreeze(unfreeze(attempt, call.get(0), call.get(1)))
                .build();
    }

    private TokenFreezeAccountTransactionBody freeze(
            @NonNull final HtsCallAttempt attempt, @NonNull final Address token, @NonNull final Address account) {
        return TokenFreezeAccountTransactionBody.newBuilder()
                .account(attempt.addressIdConverter().convert(account))
                .token(ConversionUtils.asTokenId(attempt.nativeOperations().entityIdFactory(), token))
                .build();
    }

    private TokenUnfreezeAccountTransactionBody unfreeze(
            @NonNull final HtsCallAttempt attempt, @NonNull final Address token, @NonNull final Address account) {
        return TokenUnfreezeAccountTransactionBody.newBuilder()
                .account(attempt.addressIdConverter().convert(account))
                .token(ConversionUtils.asTokenId(attempt.nativeOperations().entityIdFactory(), token))
                .build();
    }
}
