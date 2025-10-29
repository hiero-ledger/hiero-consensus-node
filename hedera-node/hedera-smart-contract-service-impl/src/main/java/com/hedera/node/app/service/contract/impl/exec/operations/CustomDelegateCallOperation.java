// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.DelegateCallOperation;

/**
 * Hedera customization of {@link DelegateCallOperation} that immediately halts on calls to missing addresses,
 * <i>unless</i> the call is to an address in the system account range, in which case the fate of the call
 * is determined by the {@link CustomMessageCallProcessor}.
 */
public class CustomDelegateCallOperation extends DelegateCallOperation implements BasicCustomCallOperation {
    private final AddressChecks addressChecks;
    private final FeatureFlags featureFlags;

    /**
     * Constructor for custom delegate call operations.
     *
     * @param gasCalculator the gas calculator to use
     * @param addressChecks checks against addresses reserved for Hedera
     * @param featureFlags current evm module feature flags
     */
    public CustomDelegateCallOperation(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final AddressChecks addressChecks,
            @NonNull final FeatureFlags featureFlags) {
        super(requireNonNull(gasCalculator));
        this.addressChecks = requireNonNull(addressChecks);
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
        // Prevent delegate calls during hook execution. The only exception is calls to system contracts.
        if (FrameUtils.isHookExecution(frame) && !isRedirectFromNativeEntity(frame)) {
            return new OperationResult(0, ExceptionalHaltReason.INVALID_OPERATION);
        }
        return BasicCustomCallOperation.super.executeChecked(frame, evm);
    }

    /**
     * Determines if the delegate call is being redirected from a native facade.
     *
     * @param frame the current message frame
     * @return true if the call is redirected from a native entity, false otherwise
     */
    private boolean isRedirectFromNativeEntity(@NonNull final MessageFrame frame) {
        final var updater = (ProxyWorldUpdater) frame.getWorldUpdater();
        final var recipient = requireNonNull(updater.getHederaAccount(frame.getRecipientAddress()));
        return recipient.isTokenFacade() || recipient.isScheduleTxnFacade() || recipient.isRegularAccount();
    }
}
