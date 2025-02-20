// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.operations.CustomizedOpcodes.CHAINID;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;

import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.AbstractOperation;

/**
 * A {@code CHAINID} operation that uses the {@link com.hedera.node.config.data.ContractsConfig} from the
 * frame context variables to provide the chain id. (The
 * {@link com.hedera.node.app.service.contract.impl.exec.TransactionProcessor} must always set
 * the config in the frame context, since in principle it could vary with every transaction.)
 */
public class CustomChainIdOperation extends AbstractOperation {
    private final long cost;

    /**
     * @param gasCalculator the gas calculator to use
     */
    public CustomChainIdOperation(@NonNull final GasCalculator gasCalculator) {
        super(CHAINID.opcode(), "CHAINID", 0, 1, gasCalculator);
        this.cost = Objects.requireNonNull(gasCalculator).getBaseTierGasCost();
    }

    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        if (frame.getRemainingGas() < cost) {
            return new OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
        }
        final var chainIdAsInt =
                configOf(frame).getConfigData(ContractsConfig.class).chainId();
        final var chainId = Bytes32.fromHexStringLenient(Integer.toString(chainIdAsInt, 16));
        frame.pushStackItem(chainId);
        return new OperationResult(cost, null);
    }
}
