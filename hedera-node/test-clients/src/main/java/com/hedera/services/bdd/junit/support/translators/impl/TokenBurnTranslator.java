// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Set;

public class TokenBurnTranslator implements BlockTransactionPartsTranslator {
    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @Nullable final List<TraceData> tracesSoFar,
            @NonNull final List<TraceData> followingUnitTraces) {
        requireNonNull(parts);
        requireNonNull(baseTranslator);
        requireNonNull(remainingStateChanges);
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> {
                    if (parts.status() == SUCCESS) {
                        final var op = parts.body().tokenBurnOrThrow();
                        final var tokenId = op.tokenOrThrow();
                        final var serialsBurned = Set.copyOf(op.serialNumbers());
                        final var numSerialsBurned = serialsBurned.size();
                        final long newTotalSupply;
                        if (numSerialsBurned > 0) {
                            newTotalSupply = baseTranslator.tokenTypeOrThrow(tokenId) == NON_FUNGIBLE_UNIQUE
                                    ? baseTranslator.newTotalSupply(tokenId, -numSerialsBurned)
                                    : baseTranslator.newTotalSupply(tokenId, 0);
                        } else {
                            final var amountBurned = op.amount();
                            newTotalSupply = baseTranslator.newTotalSupply(tokenId, -amountBurned);
                        }
                        receiptBuilder.newTotalSupply(newTotalSupply);
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }
}
