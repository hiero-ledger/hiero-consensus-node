// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

public class CryptoUpdateTranslator implements BlockTransactionPartsTranslator {
    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @Nullable final List<TraceData> tracesSoFar,
            @NonNull final List<TraceData> followingUnitTraces) {
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> {
                    if (parts.status() == SUCCESS) {
                        final var op = parts.body().cryptoUpdateAccountOrThrow();
                        final var targetId = op.accountIDToUpdateOrThrow();
                        final var iter = remainingStateChanges.listIterator();
                        while (iter.hasNext()) {
                            final var stateChange = iter.next();
                            if (stateChange.hasMapUpdate()
                                    && stateChange
                                            .mapUpdateOrThrow()
                                            .keyOrThrow()
                                            .hasAccountIdKey()
                                    && stateChange
                                            .mapUpdateOrThrow()
                                            .valueOrThrow()
                                            .hasAccountValue()) {
                                final var account = stateChange
                                        .mapUpdateOrThrow()
                                        .valueOrThrow()
                                        .accountValueOrThrow();
                                if (matches(targetId, account)) {
                                    receiptBuilder.accountID(account.accountIdOrThrow());
                                    return;
                                }
                            }
                        }
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }

    private boolean matches(@NonNull final AccountID accountId, @NonNull final Account account) {
        return switch (accountId.account().kind()) {
            case UNSET -> throw new IllegalStateException("Account ID has no kind");
            case ACCOUNT_NUM -> account.accountIdOrThrow().equals(accountId);
            case ALIAS -> account.alias().equals(accountId.aliasOrThrow());
        };
    }
}
