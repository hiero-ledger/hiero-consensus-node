// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Defines a functional interface for translating a block transaction and the remaining state changes from its origin
 * {@link BlockTransactionalUnit} into a {@link SingleTransactionRecord}.
 */
@FunctionalInterface
public interface BlockTransactionPartsTranslator {
    /**
     * Translate the given block transaction parts into a single transaction record. The remaining state changes are
     * provided to allow for the translation of the record to include the state changes that were not part of the
     * transaction.
     * <p>
     * If the translator needs a state change with a particular {@link StateChange#stateId()} to complete its
     * translation it <b>must</b> follow two principles:
     * <ol>
     *     <li>It must use the first occurrence of that state change from the given list.</li>
     *     <li>It must remove that state change from the list before returning the translated record.</li>
     * </ol>
     *
     * @param parts the parts of the transaction
     * @param baseTranslator the base translator
     * @param remainingStateChanges the state changes remaining to be processed
     * @param tracesSoFar if not null, the traces up to this point of the transaction unit
     * @param followingUnitTraces any additional trace data associated with the transaction unit
     * @return the translated record
     */
    SingleTransactionRecord translate(
            @NonNull BlockTransactionParts parts,
            @NonNull BaseTranslator baseTranslator,
            @NonNull List<StateChange> remainingStateChanges,
            @Nullable List<TraceData> tracesSoFar,
            @NonNull List<TraceData> followingUnitTraces);
}
