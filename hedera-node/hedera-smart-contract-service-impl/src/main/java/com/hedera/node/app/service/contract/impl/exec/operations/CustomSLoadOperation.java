// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.hapi.streams.SidecarType.CONTRACT_STATE_CHANGE;
import static com.hedera.node.app.service.contract.impl.exec.operations.utils.OpUtils.maybeTrackReadIn;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.streams.SidecarType;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.SLoadOperation;

/**
 * A wrapper around {@link SLoadOperation} that takes the extra step of tracking the read storage value if
 * {@link FeatureFlags#isSidecarEnabled(MessageFrame, SidecarType)} returns true for the
 * {@link SidecarType#CONTRACT_STATE_CHANGE} type.
 */
public class CustomSLoadOperation extends DelegatingOperation {
    private final FeatureFlags featureFlags;

    /**
     * @param featureFlags current evm module feature flags
     * @param delegate the delegate operation
     */
    public CustomSLoadOperation(@NonNull final FeatureFlags featureFlags, @NonNull final SLoadOperation delegate) {
        super(delegate);
        this.featureFlags = requireNonNull(featureFlags);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        requireNonNull(evm);
        requireNonNull(frame);

        final var key = frame.getStackItem(0);
        final var result = super.execute(frame, evm);
        if (result.getHaltReason() == null && featureFlags.isSidecarEnabled(frame, CONTRACT_STATE_CHANGE)) {
            // The base SLOAD operation returns its read value on the stack
            final var value = frame.getStackItem(0);
            maybeTrackReadIn(frame, UInt256.fromBytes(key), UInt256.fromBytes(value));
        }
        return result;
    }
}
