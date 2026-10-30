// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.ExtCodeSizeOperation;

/**
 * Customization of {@link ExtCodeSizeOperation} that treats every long-zero address for an account
 * below {@code 0.0.1001} as having zero code; and otherwise requires the account to be present or
 * halts the frame with
 * {@link com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason#INVALID_SOLIDITY_ADDRESS}.
 */
public class CustomExtCodeSizeOperation extends ExtCodeSizeOperation implements BasicCustomAccountAccessOperation {
    private final AddressChecks addressChecks;
    private final FeatureFlags featureFlags;

    /**
     * @param gasCalculator the gas calculator to use
     * @param addressChecks checks against addresses reserved for Hedera
     * @param featureFlags current evm module feature flags
     */
    public CustomExtCodeSizeOperation(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final AddressChecks addressChecks,
            @NonNull final FeatureFlags featureFlags) {
        super(Objects.requireNonNull(gasCalculator));
        this.addressChecks = Objects.requireNonNull(addressChecks);
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
        return cost(accountIsWarm);
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
