// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Defines a translator for transactions that can remove pending airdrops.
 */
public enum AirdropRemovalTranslator implements BlockTransactionPartsTranslator {
    AIRDROP_REMOVAL_TRANSLATOR;

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
                        for (final var stateChange : remainingStateChanges) {
                            if (stateChange.hasMapDelete()
                                    && stateChange
                                            .mapDeleteOrThrow()
                                            .keyOrThrow()
                                            .hasPendingAirdropIdKey()) {
                                baseTranslator.remove(stateChange
                                        .mapDeleteOrThrow()
                                        .keyOrThrow()
                                        .pendingAirdropIdKeyOrThrow());
                            }
                        }
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }
}
