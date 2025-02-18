/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.operations.utils.OpUtils.maybeTrackReadIn;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.SLoadOperation;

/**
 * A wrapper around {@link SLoadOperation} that takes the extra step of tracking the read storage value.
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
        if (result.getHaltReason() == null) {
            // The base SLOAD operation returns its read value on the stack
            final var value = frame.getStackItem(0);
            maybeTrackReadIn(frame, UInt256.fromBytes(key), UInt256.fromBytes(value));
        }
        return result;
    }
}
