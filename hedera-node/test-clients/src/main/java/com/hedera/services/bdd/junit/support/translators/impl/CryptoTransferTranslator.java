// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.HookId;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.ScopedTraceData;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.HookMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Translates a crypto transfer transaction into a {@link SingleTransactionRecord}.
 */
public class CryptoTransferTranslator implements BlockTransactionPartsTranslator {
    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @Nullable final List<TraceData> tracesSoFar,
            @NonNull final List<ScopedTraceData> followingUnitTraces,
            @Nullable final HookId executingHookId,
            @Nullable final HookMetadata hookMetadata) {
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> recordBuilder.assessedCustomFees(parts.assessedCustomFees()),
                remainingStateChanges,
                followingUnitTraces,
                executingHookId);
    }
}
