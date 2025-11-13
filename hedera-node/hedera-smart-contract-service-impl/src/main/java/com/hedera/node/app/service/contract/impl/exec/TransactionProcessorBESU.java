// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.accessTrackerFor;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.gas.GasCharges;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameBuilder;
import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;

/**
 * Modeled after the Besu {@code MainnetTransactionProcessor}, so that all four HAPI
 * contract operations ({@code ContractCall}, {@code ContractCreate}, {@code EthereumTransaction},
 * {@code ContractCallLocal}) can reduce to a single code path.
 */
public class TransactionProcessorBESU extends TransactionProcessor {
    private final FrameBuilder frameBuilder;
    private final FrameRunner frameRunner;
    private final ContractCreationProcessor contractCreation;

    public TransactionProcessorBESU(
            @NonNull final FrameBuilder frameBuilder,
            @NonNull final FrameRunner frameRunner,
            @NonNull final CustomGasCharging gasCharging,
            @NonNull final CustomMessageCallProcessor messageCall,
            @NonNull final ContractCreationProcessor contractCreation,
            @NonNull final FeatureFlags featureFlags,
            @NonNull final CodeFactory codeFactory) {
        super(gasCharging, messageCall,featureFlags, codeFactory);
        this.frameBuilder = requireNonNull(frameBuilder);
        this.frameRunner = requireNonNull(frameRunner);
        this.contractCreation = requireNonNull(contractCreation);
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
        final var parties = computeInvolvedPartiesOrAbort(transaction, updater, config);
        return processTransactionWithParties(
                transaction, updater, context, tracer, config, opsDurationCounter, parties);
    }

    private HederaEvmTransactionResult processTransactionWithParties(
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater updater,
            @NonNull final HederaEvmContext context,
            @NonNull final ActionSidecarContentTracer tracer,
            @NonNull final Configuration config,
            @NonNull final OpsDurationCounter opsDurationCounter,
            @NonNull final InvolvedParties parties) {
        // If it is hook dispatch, skip gas charging because gas is pre-paid in cryptoTransfer already
        final var gasCharges = transaction.hookOwnerAddress() != null
                ? GasCharges.NONE
                : gasCharging.chargeForGas(parties.sender(), parties.relayer(), context, updater, transaction);
        final var initialFrame = frameBuilder.buildInitialFrameWith(
                transaction,
                updater,
                context,
                config,
                opsDurationCounter,
                featureFlags,
                parties.sender().getAddress(),
                parties.receiverAddress(),
                gasCharges.intrinsicGas(),
                codeFactory);

        // Compute the result of running the frame to completion
        final var result = frameRunner.runToCompletion(
                transaction.gasLimit(), parties.senderId(), initialFrame, tracer, messageCall, contractCreation);

        // Maybe refund some of the charged fees before committing if not a hook dispatch
        // Note that for hook dispatch, gas is charged during cryptoTransfer and will not be refunded once
        // hook is executed
        if (transaction.hookOwnerAddress() == null) {
            gasCharging.maybeRefundGiven(
                    transaction.unusedGas(result.gasUsed()),
                    gasCharges.relayerAllowanceUsed(),
                    parties.sender(),
                    parties.relayer(),
                    context,
                    updater);
        }
        initialFrame.getSelfDestructs().forEach(updater::deleteAccount);

        // Tries to commit and return the original result; returns a fees-only result on resource exhaustion
        return safeCommit(result, transaction, updater, context, accessTrackerFor(initialFrame));
    }

}
