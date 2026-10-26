// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static org.hyperledger.besu.evm.internal.Words.clampedToLong;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.ExtCodeCopyOperation;

/**
 * Customization of {@link ExtCodeCopyOperation} that treats every long-zero address for an account
 * below {@code 0.0.1001} as having zero code; and otherwise requires the account to be present or
 * halts the frame with {@link CustomExceptionalHaltReason#INVALID_SOLIDITY_ADDRESS}.
 */
public class CustomExtCodeCopyOperation extends ExtCodeCopyOperation implements BasicCustomAccountAccessOperation {
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
    public AddressChecks addressChecks() {
        return addressChecks;
    }

    @Override
    public FeatureFlags featureFlags() {
        return featureFlags;
    }

    @Override
    public GasCalculator gasCalculator() {
        return super.gasCalculator();
    }

    @Override
    public long cost(@NonNull final MessageFrame frame, final boolean accountIsWarm) {
        final var memOffset = clampedToLong(frame.getStackItem(1));
        final var numBytes = clampedToLong(frame.getStackItem(3));
        return cost(frame, memOffset, numBytes, accountIsWarm);
    }

    @Override
    public void shortCircuit(@NonNull final MessageFrame frame) {
        final var memOffset = clampedToLong(frame.getStackItem(1));
        final var sourceOffset = clampedToLong(frame.getStackItem(2));
        final var numBytes = clampedToLong(frame.getStackItem(3));
        frame.writeMemory(memOffset, sourceOffset, numBytes, Bytes.EMPTY);
        frame.popStackItems(4);
    }

    @Override
    public OperationResult executeUnchecked(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        return super.execute(frame, evm);
    }

    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        return executeChecked(frame, evm);
    }
}
