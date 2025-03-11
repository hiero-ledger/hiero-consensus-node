// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.hyperledger.besu.evm.tracing.OperationTracer;

/**
 * Executes a HAPI transaction within a "standalone" context based on a previously established {@link State}.
 */
@FunctionalInterface
public interface TransactionExecutor {
    /**
     * Executes a HAPI transaction within a "standalone" context based on a previously established {@link State} at
     * the given consensus time. If the transaction is a contract operation, the given Besu {@link OperationTracer}
     * instances will be given to the EVM. If it is not a contract operation, the operation tracers will be ignored.
     * <p>
     * Returns one or more {@link SingleTransactionRecord} instances from executing the transaction.
     * @param transactionBody the HAPI transaction body to execute
     * @param consensusNow the consensus time at which the transaction is to be executed
     * @param operationTracers the Besu {@link OperationTracer} instances to use for contract operations
     * @return one or more {@link SingleTransactionRecord}s for the executed transaction
     * @throws RuntimeException if the executor cannot build a dispatch from the given transaction
     */
    List<SingleTransactionRecord> execute(
            @NonNull TransactionBody transactionBody,
            @NonNull Instant consensusNow,
            @NonNull OperationTracer... operationTracers);
}
