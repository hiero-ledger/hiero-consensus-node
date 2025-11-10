// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static java.util.Objects.requireNonNull;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameBuilder;
import com.hedera.node.app.service.contract.impl.utils.TODO;
import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.*;
import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * Modeled after the Besu {@code MainnetTransactionProcessor}, so that all four HAPI
 * contract operations ({@code ContractCall}, {@code ContractCreate}, {@code EthereumTransaction},
 * {@code ContractCallLocal}) can reduce to a single code path.
 */
public abstract class TransactionProcessor {
    final FeatureFlags featureFlags;

    public TransactionProcessor(
            @NonNull final FeatureFlags featureFlags
                                ) {
        this.featureFlags = requireNonNull(featureFlags);
    }


    public static TransactionProcessor make(
            FrameBuilder frameBuilder,
            FrameRunner frameRunner,
            CustomGasCharging gasCharging,
            CustomMessageCallProcessor messageCall,
            ContractCreationProcessor contractCreation,
            FeatureFlags featureFlags,
            CodeFactory codeFactory) {
        throw new TODO("Static factory uses global var to pick BESU vs BEVM");
    }


    /**
     * Returns the feature flags used by this processor.
     *
     * @return the feature flags
     */
    public FeatureFlags featureFlags() {
        return featureFlags;
    }

    /**
     * Process the given transaction, returning the result of running it to completion
     * and committing to the given updater.
     *
     * @param transaction the transaction to process
     * @param updater the world updater to commit to
     * @param context the context to use
     * @param tracer the tracer to use
     * @param config the node configuration
     * @return the result of running the transaction to completion
     */
    public HederaEvmTransactionResult processTransaction(
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater updater,
            @NonNull final HederaEvmContext context,
            @NonNull final ActionSidecarContentTracer tracer,
            @NonNull final Configuration config,
            @NonNull final OpsDurationCounter opsDurationCounter) {
        //final var parties = computeInvolvedPartiesOrAbort(transaction, updater, config);
        //return processTransactionWithParties(
        //        transaction, updater, context, tracer, config, opsDurationCounter, parties);
        throw new TODO();
    }
}
