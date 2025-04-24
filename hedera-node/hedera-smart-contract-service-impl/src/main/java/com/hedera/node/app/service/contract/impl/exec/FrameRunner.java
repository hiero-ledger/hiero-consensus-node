// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.getAndClearPropagatedCallFailure;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.getHederaOpsDuration;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.maybeNext;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.setPropagatedCallFailure;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult.failureFrom;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult.successFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmContractId;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asNumberedContractId;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.MessageFrame.State.COMPLETED_SUCCESS;
import static org.hyperledger.besu.evm.frame.MessageFrame.State.EXCEPTIONAL_HALT;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.hevm.HevmPropagatedCallFailure;
import com.swirlds.state.lifecycle.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;

/**
 * An infrastructure service that runs the EVM transaction beginning with the given {@link MessageFrame}
 * to completion and returns the result.
 */
@Singleton
public class FrameRunner {
    private final CustomGasCalculator gasCalculator;
    private final EntityIdFactory entityIdFactory;

    /**
     * @param gasCalculator the gas calculator to be used
     */
    @Inject
    public FrameRunner(
            @NonNull final CustomGasCalculator gasCalculator, @NonNull final EntityIdFactory entityIdFactory) {
        this.gasCalculator = gasCalculator;
        this.entityIdFactory = entityIdFactory;
    }

    /**
     * Runs the EVM transaction implied by the given {@link MessageFrame} to completion using the provided
     * {@link org.hyperledger.besu.evm.processor.AbstractMessageProcessor} implementations, and returns the result.
     *
     * @param gasLimit the gas limit for the transaction
     * @param frame the frame to run
     * @param senderId the Hedera id of the sending account
     * @param tracer the tracer to use
     * @param messageCall the message call processor to use
     * @param contractCreation the contract creation processor to use
     * @return the result of the transaction
     */
    public HederaEvmTransactionResult runToCompletion(
            final long gasLimit,
            @NonNull final AccountID senderId,
            @NonNull final MessageFrame frame,
            @NonNull final ActionSidecarContentTracer tracer,
            @NonNull final CustomMessageCallProcessor messageCall,
            @NonNull final ContractCreationProcessor contractCreation) {
        requireNonNull(frame);
        requireNonNull(tracer);
        requireNonNull(senderId);
        requireNonNull(messageCall);
        requireNonNull(contractCreation);

        final var recipientAddress = frame.getRecipientAddress();
        // We compute the called contract's Hedera id up front because it could
        // selfdestruct, preventing us from looking up its id after the fact
        final var recipientMetadata = computeRecipientMetadata(frame, recipientAddress);

        // Now run the transaction implied by the frame
        tracer.traceOriginAction(frame);
        final var stack = frame.getMessageFrameStack();
        while (!stack.isEmpty()) {
            runToCompletion(stack.peekFirst(), tracer, messageCall, contractCreation);
        }
        tracer.sanitizeTracedActions(frame);

        // And return the result, success or failure
        final var gasUsed = effectiveGasUsed(gasLimit, frame);
        final var opsDuration = getHederaOpsDuration(frame);
        if (frame.getState() == COMPLETED_SUCCESS) {
            return successFrom(
                    gasUsed,
                    opsDuration,
                    senderId,
                    recipientMetadata.hederaId(),
                    asEvmContractId(entityIdFactory, recipientAddress),
                    frame,
                    tracer);
        } else {
            return failureFrom(gasUsed, opsDuration, senderId, frame, recipientMetadata.postFailureHederaId(), tracer);
        }
    }

    private record RecipientMetadata(boolean isPendingCreation, @NonNull ContractID hederaId) {
        private RecipientMetadata {
            requireNonNull(hederaId);
        }

        public @Nullable ContractID postFailureHederaId() {
            return isPendingCreation ? null : hederaId;
        }
    }

    private RecipientMetadata computeRecipientMetadata(
            @NonNull final MessageFrame frame, @NonNull final Address address) {
        if (isLongZero(entityIdFactory, address)) {
            return new RecipientMetadata(false, asNumberedContractId(entityIdFactory, address));
        } else {
            final var updater = proxyUpdaterFor(frame);
            return new RecipientMetadata(updater.getPendingCreation() != null, updater.getHederaContractId(address));
        }
    }

    private void runToCompletion(
            @NonNull final MessageFrame frame,
            @NonNull final ActionSidecarContentTracer tracer,
            @NonNull final CustomMessageCallProcessor messageCall,
            @NonNull final ContractCreationProcessor contractCreation) {
        final var executor =
                switch (frame.getType()) {
                    case MESSAGE_CALL -> messageCall;
                    case CONTRACT_CREATION -> contractCreation;
                };
        executor.process(frame, tracer);

        frame.getExceptionalHaltReason().ifPresent(haltReason -> propagateHaltException(frame, haltReason));
        // For mono-service compatibility, we need to also halt the frame on the stack that
        // executed the CALL operation whose dispatched frame failed due to a missing receiver
        // signature; since mono-service did that check as part of the CALL operation itself.
        final var maybeFailureToPropagate = getAndClearPropagatedCallFailure(frame);
        if (maybeFailureToPropagate != HevmPropagatedCallFailure.NONE) {
            maybeNext(frame).ifPresent(f -> {
                f.setState(EXCEPTIONAL_HALT);
                f.setExceptionalHaltReason(maybeFailureToPropagate.exceptionalHaltReason());
                // Finalize the CONTRACT_ACTION for the propagated halt frame as well
                maybeFailureToPropagate
                        .exceptionalHaltReason()
                        .ifPresent(reason -> tracer.tracePostExecution(
                                f, new Operation.OperationResult(frame.getRemainingGas(), reason)));
            });
        }
    }

    private long effectiveGasUsed(final long gasLimit, @NonNull final MessageFrame frame) {
        var nominalUsed = gasLimit - frame.getRemainingGas();
        final var selfDestructRefund = gasCalculator.getSelfDestructRefundAmount()
                * Math.min(frame.getSelfDestructs().size(), nominalUsed / gasCalculator.getMaxRefundQuotient());
        nominalUsed -= (selfDestructRefund + frame.getGasRefund());
        final var maxRefundPercent = contractsConfigOf(frame).maxRefundPercentOfGasLimit();
        return Math.max(nominalUsed, gasLimit - gasLimit * maxRefundPercent / 100);
    }

    // potentially other cases could be handled here if necessary
    private void propagateHaltException(MessageFrame frame, ExceptionalHaltReason haltReason) {
        if (haltReason.equals(INSUFFICIENT_CHILD_RECORDS)) {
            setPropagatedCallFailure(frame, HevmPropagatedCallFailure.RESULT_CANNOT_BE_EXTERNALIZED);
        } else if (haltReason.equals(INVALID_CONTRACT_ID)) {
            setPropagatedCallFailure(frame, HevmPropagatedCallFailure.INVALID_CONTRACT_ID);
        }
    }
}
