// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.EVM_VERSIONS;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.annotations.QueryScope;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion;
import com.hedera.node.app.service.contract.impl.infra.HevmStaticTransactionFactory;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.inject.Inject;

/**
 * A utility class for running the {@code processTransaction()} call implied by the in-scope
 * {@link QueryContext}. Analogous to {@link ContextTransactionProcessor} but for queries.
 */
@QueryScope
public class ContextQueryProcessor implements Callable<CallOutcome> {
    private final QueryContext context;
    private final HederaEvmContext hederaEvmContext;
    private final ActionSidecarContentTracer tracer;
    private final ProxyWorldUpdater worldUpdater;
    private final HevmStaticTransactionFactory hevmStaticTransactionFactory;
    private final Map<HederaEvmVersion, TransactionProcessor> processors;

    /**
     * @param context the context of the query
     * @param hederaEvmContext the hedera EVM context
     * @param tracer the tracer to use
     * @param worldUpdater the world updater for the transaction
     * @param hevmStaticTransactionFactory the factory to create Hedera EVM transaction for static calls
     * @param processors a map from the version of the Hedera EVM to the transaction processor
     */
    @Inject
    public ContextQueryProcessor(
            @NonNull final QueryContext context,
            @NonNull final HederaEvmContext hederaEvmContext,
            @NonNull final ActionSidecarContentTracer tracer,
            @NonNull final ProxyWorldUpdater worldUpdater,
            @NonNull final HevmStaticTransactionFactory hevmStaticTransactionFactory,
            @NonNull final Map<HederaEvmVersion, TransactionProcessor> processors) {
        this.context = requireNonNull(context);
        this.tracer = requireNonNull(tracer);
        this.processors = requireNonNull(processors);
        this.worldUpdater = requireNonNull(worldUpdater);
        this.hederaEvmContext = requireNonNull(hederaEvmContext);
        this.hevmStaticTransactionFactory = requireNonNull(hevmStaticTransactionFactory);
    }

    @Override
    public CallOutcome call() {
        try {
            // Try to translate the HAPI operation to a Hedera EVM transaction, throw HandleException on failure
            final var hevmTransaction = hevmStaticTransactionFactory.fromHapiQuery(context.query());

            final var contractsConfig = context.configuration().getConfigData(ContractsConfig.class);
            // Get the appropriate processor for the EVM version
            final var processor = processors.get(EVM_VERSIONS.get(contractsConfig.evmVersion()));

            // Process the transaction
            final var result = processor.processTransaction(
                    hevmTransaction, worldUpdater, hederaEvmContext, tracer, context.configuration());

            // Return the outcome (which cannot include sidecars to be externalized, since this is a query)
            return CallOutcome.fromResultsWithoutSidecars(
                    result.asQueryResult(worldUpdater), result.asEvmQueryResult(), null, null, null, result);
        } catch (final HandleException e) {
            final var op = context.query().contractCallLocalOrThrow();
            final var senderId = op.hasSenderId() ? op.senderIdOrThrow() : requireNonNull(context.payer());

            final var hevmTransaction = hevmStaticTransactionFactory.fromHapiQueryException(context.query(), e);
            final var result = HederaEvmTransactionResult.fromAborted(
                    senderId,
                    hevmTransaction.contractId(),
                    requireNonNull(hevmTransaction.exception()).getStatus());
            return CallOutcome.fromResultsWithoutSidecars(
                    result.asQueryResult(worldUpdater), result.asEvmQueryResult(), null, null, null, result);
        }
    }
}
