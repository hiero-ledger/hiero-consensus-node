// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class HederaEvmTransactionProcessor {
    private final Map<HederaEvmVersion, TransactionProcessor> transactionProcessors;

    @Inject
    public HederaEvmTransactionProcessor(
            @NonNull final Map<HederaEvmVersion, TransactionProcessor> transactionProcessors) {
        this.transactionProcessors = requireNonNull(transactionProcessors);
    }

    public HederaEvmTransactionResult process(
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater updater,
            @NonNull final HederaEvmContext context,
            @NonNull final HederaEvmVersion version,
            @NonNull final ActionSidecarContentTracer tracer,
            @NonNull final Configuration config,
            @NonNull final OpsDurationCounter opsDurationCounter) {
        return requireNonNull(transactionProcessors.get(version))
                .processTransaction(
                        requireNonNull(transaction),
                        requireNonNull(updater),
                        requireNonNull(context),
                        requireNonNull(tracer),
                        requireNonNull(config),
                        requireNonNull(opsDurationCounter));
    }
}
