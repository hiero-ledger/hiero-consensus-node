// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.EVM_ADDRESS_LENGTH_AS_INT;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.throwIfUnsuccessfulCall;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.hapi.utils.fee.SmartContractFeeBuilder;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONTRACT_CALL}.
 */
@Singleton
public class ContractCallHandler extends AbstractContractTransactionHandler {
    /**
     * Constructs a {@link ContractCallHandler} with the given {@link Provider} and {@link GasCalculator}.
     *
     * @param provider the provider to be used
     * @param gasCalculator the gas calculator to be used
     */
    @Inject
    public ContractCallHandler(
            @NonNull final Provider<TransactionComponent.Factory> provider,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ContractServiceComponent component) {
        super(provider, gasCalculator, component);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        // Create the transaction-scoped component
        final var component = getTransactionComponent(context, CONTRACT_CALL);

        // Run its in-scope transaction and get the outcome
        final var outcome = component.contextTransactionProcessor().call();

        // Assemble the appropriate top-level record for the result
        final var streamBuilder = context.savepointStack().getBaseBuilder(ContractCallStreamBuilder.class);
        outcome.addCallDetailsTo(streamBuilder, context);

        throwIfUnsuccessfulCall(outcome, component.hederaOperations(), streamBuilder);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) {
        // No non-payer signatures to verify
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        try {
            final var txn = context.body();
            final var op = txn.contractCallOrThrow();
            mustExist(op.contractID(), INVALID_CONTRACT_ID);
            if (op.contractID().hasEvmAddress()) {
                validateTruePreCheck(
                        op.contractID().evmAddressOrThrow().length() == EVM_ADDRESS_LENGTH_AS_INT, INVALID_CONTRACT_ID);
            }

            // TODO: Revisit baselineGas with Pectra support epic
            final var intrinsicGas = gasCalculator.transactionIntrinsicGasCost(
                    Bytes.wrap(op.functionParameters().toByteArray()), false, 0L);
            validateTruePreCheck(op.gas() >= intrinsicGas, INSUFFICIENT_GAS);
        } catch (@NonNull final Exception e) {
            bumpExceptionMetrics(CONTRACT_CALL, e);
            throw e;
        }
    }

    @Override
    protected /*abstract*/ @NonNull FeeData getFeeMatrices(
            @NonNull final SmartContractFeeBuilder usageEstimator,
            @NonNull final com.hederahashgraph.api.proto.java.TransactionBody txBody,
            @NonNull final SigValueObj sigValObj) {
        return usageEstimator.getContractCallTxFeeMatrices(txBody, sigValObj);
    }
}
