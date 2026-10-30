// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.BalanceOperation;

/**
 * A Hedera customization of the Besu {@link org.hyperledger.besu.evm.operation.BalanceOperation} that
 * makes the balance of every system account invisible to the EVM (added in v0.38); and otherwise requires
 * the account to be present or halts the frame with
 * {@link com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason#INVALID_SOLIDITY_ADDRESS}.
 */
public class CustomBalanceOperation extends BalanceOperation implements BasicCustomAccountAccessOperation {
    private final AddressChecks addressChecks;
    private final FeatureFlags featureFlags;

    /**
     * Constructor for custom balance operations.
     * @param gasCalculator the gas calculator to use
     * @param addressChecks checks against addresses reserved for Hedera
     * @param featureFlags current evm module feature flags
     */
    public CustomBalanceOperation(
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
        return cost(accountIsWarm);
    }

    @Override
    public boolean isShortCircuited(@NonNull final Address address) {
        return addressChecks.isSystemAccount(address);
    }

    @Override
    public OperationResult executeUnchecked(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        return super.execute(frame, evm);
    }

    @Override
    public OperationResult underflowResponse() {
        return new OperationResult(cost(false), ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
    }

    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        return executeChecked(frame, evm);
    }
}
