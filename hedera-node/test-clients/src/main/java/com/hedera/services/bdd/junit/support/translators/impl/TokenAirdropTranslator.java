// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.token.impl.comparator.TokenComparators.PENDING_AIRDROP_ID_COMPARATOR;
import static java.util.Comparator.comparing;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.transaction.PendingAirdropRecord;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Defines a translator for a token airdrop into a {@link SingleTransactionRecord}.
 */
public class TokenAirdropTranslator implements BlockTransactionPartsTranslator {
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
                        recordBuilder.assessedCustomFees(parts.assessedCustomFees());
                        final List<PendingAirdropRecord> pendingAirdrops = new ArrayList<>();
                        // Note we assume token airdrops are only top-level transactions, which is currently true
                        for (final var stateChange : remainingStateChanges) {
                            if (stateChange.hasMapUpdate()
                                    && stateChange
                                            .mapUpdateOrThrow()
                                            .keyOrThrow()
                                            .hasPendingAirdropIdKey()) {
                                final var pendingAirdropId = stateChange
                                        .mapUpdateOrThrow()
                                        .keyOrThrow()
                                        .pendingAirdropIdKeyOrThrow();
                                final var pendingAirdropValue = stateChange
                                        .mapUpdateOrThrow()
                                        .valueOrThrow()
                                        .accountPendingAirdropValueOrThrow()
                                        .pendingAirdropValue();
                                final var pendingAirdropRecord =
                                        new PendingAirdropRecord(pendingAirdropId, pendingAirdropValue);
                                if (baseTranslator.track(pendingAirdropRecord)) {
                                    pendingAirdrops.add(pendingAirdropRecord);
                                }
                            }
                        }
                        // Pending airdrop ids are unique, so we don't need to worry about duplicates
                        pendingAirdrops.sort(comparing(
                                PendingAirdropRecord::pendingAirdropIdOrThrow, PENDING_AIRDROP_ID_COMPARATOR));
                        recordBuilder.newPendingAirdrops(pendingAirdrops);
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }
}
