// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractRequired;
import static com.hedera.node.app.service.contract.impl.exec.utils.OperationUtils.isDeficientGas;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.InvalidAddressContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;

/**
 * Interface to avoid duplicating the exact same {@link org.hyperledger.besu.evm.operation.AbstractCallOperation#execute(MessageFrame, EVM)}
 * override in {@link CustomCallOperation}, {@link CustomStaticCallOperation}, and {@link CustomDelegateCallOperation}.
 */
public interface BasicCustomCallOperation {
    /**
     * Response for underflow.
     */
    Operation.OperationResult UNDERFLOW_RESPONSE =
            new Operation.OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);

    /**
     * Returns the {@link AddressChecks} instance used to determine whether a call is to a missing address.
     *
     * @return the {@link AddressChecks} instance used to determine whether a call is to a missing address
     */
    AddressChecks addressChecks();

    /**
     * Returns the {@link FeatureFlags} instance.
     *
     * @return the {@link FeatureFlags} instance.
     */
    FeatureFlags featureFlags();

    /**
     * Returns the address to which the {@link org.hyperledger.besu.evm.operation.AbstractCallOperation} being
     * customized is targeted.
     *
     * @param frame the frame in which the call is being made
     * @return the address to which the call is being made
     */
    Address to(@NonNull MessageFrame frame);

    /**
     * Returns the gas calculator used for gas cost calculations.
     *
     * @return the gas calculator
     */
    GasCalculator gasCalculator();

    /**
     * Returns the gas stipend for the call from the stack.
     *
     * @param frame the frame in which the call is being made
     * @return the gas stipend
     */
    long gas(@NonNull MessageFrame frame);

    /**
     * Returns the input data offset from the stack.
     *
     * @param frame the frame in which the call is being made
     * @return the input data offset
     */
    long inputDataOffset(@NonNull MessageFrame frame);

    /**
     * Returns the input data length from the stack.
     *
     * @param frame the frame in which the call is being made
     * @return the input data length
     */
    long inputDataLength(@NonNull MessageFrame frame);

    /**
     * Returns the output data offset from the stack.
     *
     * @param frame the frame in which the call is being made
     * @return the output data offset
     */
    long outputDataOffset(@NonNull MessageFrame frame);

    /**
     * Returns the output data length from the stack.
     *
     * @param frame the frame in which the call is being made
     * @return the output data length
     */
    long outputDataLength(@NonNull MessageFrame frame);

    /**
     * Returns the value being transferred with the call.
     *
     * @param frame the frame in which the call is being made
     * @return the value being transferred
     */
    Wei value(@NonNull MessageFrame frame);

    /**
     * Returns the recipient address for the call.
     *
     * @param frame the frame in which the call is being made
     * @return the recipient address
     */
    Address address(@NonNull MessageFrame frame);

    /**
     * Calculates the gas cost for the call operation.
     *
     * @param frame the frame in which the call is being made
     * @return the gas cost
     */
    default long gasCost(@NonNull final MessageFrame frame) {
        final var stipend = gas(frame);
        final var inputOffset = inputDataOffset(frame);
        final var inputLength = inputDataLength(frame);
        final var outputOffset = outputDataOffset(frame);
        final var outputLength = outputDataLength(frame);
        final var transferValue = value(frame);
        final var recipientAddress = address(frame);

        final var staticCost = gasCalculator()
                .callOperationStaticGasCost(
                        frame,
                        stipend,
                        inputOffset,
                        inputLength,
                        outputOffset,
                        outputLength,
                        transferValue,
                        recipientAddress,
                        false);

        if (isDeficientGas(frame, staticCost)) {
            return staticCost;
        }

        return gasCalculator()
                .callOperationGasCost(
                        frame,
                        staticCost,
                        stipend,
                        inputOffset,
                        inputLength,
                        outputOffset,
                        outputLength,
                        transferValue,
                        recipientAddress,
                        false);
    }

    /**
     * Executes the {@link org.hyperledger.besu.evm.operation.AbstractCallOperation} being customized.
     *
     * @param frame the frame in which the call is being made
     * @param evm the EVM in which the call is being made
     * @return the result of the call
     */
    Operation.OperationResult executeUnchecked(@NonNull MessageFrame frame, @NonNull EVM evm);

    /**
     * The basic Hedera-specific override of {@link org.hyperledger.besu.evm.operation.AbstractCallOperation#execute(MessageFrame, EVM)}.
     * Immediately halts on calls to missing addresses, <i>unless</i> the call is to an address in the system account
     * range, in which case the fate of the call is determined by the {@link CustomMessageCallProcessor}.
     *
     * @param frame the frame in which the call is being made
     * @param evm the EVM in which the call is being made
     * @return the result of the call
     */
    default Operation.OperationResult executeChecked(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        requireNonNull(evm);
        requireNonNull(frame);
        try {
            final long cost = gasCost(frame);
            if (isDeficientGas(frame, cost)) {
                return new OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
            }

            final var toAddress = to(frame);
            if (contractRequired(frame, toAddress, featureFlags())
                    && addressChecks().isNeitherSystemNorPresent(toAddress, frame)) {
                FrameUtils.invalidAddressContext(frame)
                        .set(toAddress, InvalidAddressContext.InvalidAddressType.InvalidCallTarget);
                return new Operation.OperationResult(cost, INVALID_SOLIDITY_ADDRESS);
            }
            return executeUnchecked(frame, evm);
        } catch (UnderflowException ignore) {
            return UNDERFLOW_RESPONSE;
        }
    }
}
