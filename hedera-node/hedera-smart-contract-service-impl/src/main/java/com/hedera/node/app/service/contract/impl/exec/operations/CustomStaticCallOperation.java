// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.StaticCallOperation;

/**
 * Hedera customization of {@link StaticCallOperation} that immediately halts on calls to missing addresses,
 * <i>unless</i> the call is to an address in the system account range, in which case the fate of the call
 * is determined by the {@link CustomMessageCallProcessor}.
 */
public class CustomStaticCallOperation extends StaticCallOperation implements BasicCustomCallOperation {
    private final AddressChecks addressChecks;
    private final FeatureFlags featureFlags;

    /**
     * Constructor for custom static call operations.
     *
     * @param gasCalculator the gas calculator to use
     * @param addressChecks checks against addresses reserved for Hedera
     * @param featureFlags current evm module feature flags
     */
    public CustomStaticCallOperation(
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
    public Address to(@NonNull MessageFrame frame) {
        return super.to(frame);
    }

    @Override
    public OperationResult executeUnchecked(@NonNull MessageFrame frame, @NonNull EVM evm) {
        return super.execute(frame, evm);
    }

    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        return BasicCustomCallOperation.super.executeChecked(frame, evm);
    }
}
