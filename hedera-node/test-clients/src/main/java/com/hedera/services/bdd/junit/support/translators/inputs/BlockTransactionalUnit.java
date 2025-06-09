// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.inputs;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.TransactionID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A grouping of block stream information used as input to record translation, where all the information is
 * linked to the same {@link TransactionID} and hence is part of the same transactional unit.
 * <p>
 * May include multiple logical HAPI transactions, and the state changes they produce.
 */
public record BlockTransactionalUnit(
        @NonNull List<BlockTransactionParts> blockTransactionParts, @NonNull List<StateChange> stateChanges) {
    /**
     * Returns all trace data in this unit.
     */
    public List<TraceData> allTraces() {
        return blockTransactionParts.stream()
                .filter(BlockTransactionParts::hasTraces)
                .flatMap(parts -> parts.tracesOrThrow().stream())
                .toList();
    }
}
