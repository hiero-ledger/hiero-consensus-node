// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.hapi.utils.fee.SmartContractFeeBuilder;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent.Factory;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStates;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Provider;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * Holds some state and functionality common to the smart contract transaction handlers.
 */
public abstract class AbstractContractTransactionHandler implements TransactionHandler {

    protected final Provider<Factory> provider;
    protected final ContractServiceComponent component;
    protected final GasCalculator gasCalculator;
    protected final SmartContractFeeBuilder usageEstimator = new SmartContractFeeBuilder();

    protected AbstractContractTransactionHandler(
            @NonNull final Provider<TransactionComponent.Factory> provider,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ContractServiceComponent component) {
        this.provider = requireNonNull(provider);
        this.gasCalculator = requireNonNull(gasCalculator);
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

    @Override
    public @NonNull Fees calculateFees(@NonNull FeeContext feeContext) {
        requireNonNull(feeContext);
        final var op = feeContext.body();
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj -> getFeeMatrices(usageEstimator, fromPbj(op), sigValueObj));
    }

    /**
     * Return the fee matrix for the given transaction.  Inheritor is responsible for picking
     * the correct fee matrix for the transactions it is handling.
     * <p>
     * Used by the default implementation of `calculateFees`, above.  If inheritor overrides
     * `calculateFees` then it doesn't need to override this method.
     */
    protected /*abstract*/ @NonNull FeeData getFeeMatrices(
            @NonNull final SmartContractFeeBuilder usageEstimator,
            @NonNull final com.hederahashgraph.api.proto.java.TransactionBody txBody,
            @NonNull final SigValueObj sigValObj) {
        throw new IllegalStateException("must be overridden if `calculateFees` _not_ overridden");
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
