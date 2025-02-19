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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.precompile.AbstractPrecompiledContract;

/**
 * Abstract class of system contracts
 */
public abstract class AbstractFullContract extends AbstractPrecompiledContract {
    protected AbstractFullContract(@NonNull final String name, @NonNull final GasCalculator gasCalculator) {
        super(requireNonNull(name), requireNonNull(gasCalculator));
    }

    @Override
    public long gasRequirement(final Bytes bytes) {
        throw new UnsupportedOperationException(
                getName()
                        + " requires a MessageFrame to compute gas.  Gas requirement using the input bytes is not supported on Hedera.");
    }

    @Override
    public @NonNull PrecompileContractResult computePrecompile(final Bytes input, @NonNull final MessageFrame frame) {
        throw new UnsupportedOperationException(
                getName() + " only supports full results.  This operation is not supported on Hedera.");
    }
}
