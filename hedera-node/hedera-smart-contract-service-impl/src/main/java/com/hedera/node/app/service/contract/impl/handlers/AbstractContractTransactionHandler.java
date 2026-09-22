// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent.Factory;
import com.hedera.node.app.service.contract.impl.exec.gas.HederaGasCalculator;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStates;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Provider;

/**
 * Holds some state and functionality common to the smart contract transaction handlers.
 */
public abstract class AbstractContractTransactionHandler implements TransactionHandler {

    protected final Provider<Factory> provider;
    protected final ContractServiceComponent component;
    protected final HederaGasCalculator gasCalculator;
    protected final EntityIdFactory entityIdFactory;

    protected AbstractContractTransactionHandler(
            @NonNull final Provider<TransactionComponent.Factory> provider,
            @NonNull final HederaGasCalculator gasCalculator,
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final ContractServiceComponent component) {
        this.provider = requireNonNull(provider);
        this.gasCalculator = requireNonNull(gasCalculator);
        this.entityIdFactory = requireNonNull(entityIdFactory);
        this.component = requireNonNull(component);
    }

    /**
     * Handle common metrics for transactions that fail `pureChecks`.
     * <p>
     * (Caller is responsible to rethrow `e`.)
     */
    protected void bumpExceptionMetrics(@NonNull final HederaFunctionality functionality, @NonNull final Exception e) {
        final var contractMetrics = component.contractMetrics();
        contractMetrics.incrementRejectedTx(functionality);
        if (e instanceof PreCheckException pce && pce.responseCode() == INSUFFICIENT_GAS) {
            contractMetrics.incrementRejectedForGasTx(functionality);
        }
    }

    protected @NonNull TransactionComponent getTransactionComponent(
            @NonNull final HandleContext context, @NonNull final HederaFunctionality functionality) {
        // Non-hook calls use the default strategy
        return provider.get().create(context, functionality, EvmFrameStates.DEFAULT);
    }

    protected @NonNull TransactionComponent getTransactionComponent(
            @NonNull final HandleContext context,
            @NonNull final HederaFunctionality functionality,
            @NonNull final EvmFrameStates evmFrameStates) {
        // Hook calls can override the strategy
        return provider.get().create(context, functionality, evmFrameStates);
    }
}
