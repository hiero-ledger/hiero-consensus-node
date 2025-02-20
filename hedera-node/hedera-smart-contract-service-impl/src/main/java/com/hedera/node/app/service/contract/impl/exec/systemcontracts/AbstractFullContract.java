// SPDX-License-Identifier: Apache-2.0
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
