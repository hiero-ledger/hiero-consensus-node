// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractRequired;
import static com.hedera.node.app.service.contract.impl.exec.utils.OperationUtils.isDeficientGas;
import static org.hyperledger.besu.evm.internal.Words.clampedToLong;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.InvalidAddressContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.operation.ExtCodeCopyOperation;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * Customization of {@link ExtCodeCopyOperation} that treats every long-zero address for an account
 * below {@code 0.0.1001} as having zero code; and otherwise requires the account to be present or
 * halts the frame with {@link CustomExceptionalHaltReason#INVALID_SOLIDITY_ADDRESS}.
 */
public class CustomExtCodeCopyOperation extends ExtCodeCopyOperation {
    private static final Operation.OperationResult UNDERFLOW_RESPONSE =
            new Operation.OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
    private final AddressChecks addressChecks;
    private final FeatureFlags featureFlags;

    /**
     * @param gasCalculator the gas calculator to use
     * @param addressChecks checks against addresses reserved for Hedera
     * @param featureFlags current evm module feature flags
     */
    public CustomExtCodeCopyOperation(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final AddressChecks addressChecks,
            @NonNull final FeatureFlags featureFlags) {
        super(gasCalculator);
        this.addressChecks = addressChecks;
        this.featureFlags = featureFlags;
    }

    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        try {
            final var address = Words.toAddress(frame.getStackItem(0));
            final var memOffset = clampedToLong(frame.getStackItem(1));
            final var sourceOffset = clampedToLong(frame.getStackItem(2));
            final var numBytes = clampedToLong(frame.getStackItem(3));
            final long cost = cost(frame, memOffset, numBytes, false);
            if (isDeficientGas(frame, cost)) {
                return new OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
            }

            // Special behavior for long-zero addresses below 0.0.1001
            if (addressChecks.isNonUserAccount(address)) {
                frame.writeMemory(memOffset, sourceOffset, numBytes, Bytes.EMPTY);
                frame.popStackItems(4);
                return new OperationResult(cost, null);
            }
            // Otherwise the address must be present
            if (contractRequired(frame, address, featureFlags) && !addressChecks.isPresent(address, frame)) {
                FrameUtils.invalidAddressContext(frame)
                        .set(address, InvalidAddressContext.InvalidAddressType.NonCallTarget);
                return new OperationResult(cost, CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS);
            }
            return super.execute(frame, evm);
        } catch (UnderflowException ignore) {
            return UNDERFLOW_RESPONSE;
        }
    }
}
