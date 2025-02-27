// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.operations.CustomizedOpcodes.*;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.*;
import static org.apache.tuweni.bytes.Bytes32.leftPad;
import static org.hyperledger.besu.evm.internal.Words.clampedToLong;

import com.google.common.collect.ImmutableList;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogTopic;
import org.hyperledger.besu.evm.operation.AbstractOperation;

/**
 * A Hedera customization of the Besu {@link org.hyperledger.besu.evm.operation.LogOperation} that ensures
 * each log's {@link Log#getLogger()} returns a long-zero address, since mirror nodes always want to see
 * the actual Hedera id of the emitting contract.
 */
public class CustomLogOperation extends AbstractOperation {
    private final int numTopics;

    /**
     * Constructor for custom log operations.
     * @param numTopics number of topics
     * @param gasCalculator the gas calculator to use
     */
    public CustomLogOperation(final int numTopics, @NonNull final GasCalculator gasCalculator) {
        super(
                switch (numTopics) {
                    case 0 -> LOG0.opcode();
                    case 1 -> LOG1.opcode();
                    case 2 -> LOG2.opcode();
                    case 3 -> LOG3.opcode();
                    case 4 -> LOG4.opcode();
                    default -> throw new IllegalArgumentException(
                            "No EVM log operation supports " + numTopics + " topics");
                },
                "LOG" + numTopics,
                numTopics + 2,
                0,
                gasCalculator);

        this.numTopics = numTopics;
    }

    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        final var dataLocation = clampedToLong(frame.popStackItem());
        final var numBytes = clampedToLong(frame.popStackItem());

        final var cost = gasCalculator().logOperationGasCost(frame, dataLocation, numBytes, numTopics);
        if (frame.isStatic()) {
            return new OperationResult(cost, ExceptionalHaltReason.ILLEGAL_STATE_CHANGE);
        } else if (frame.getRemainingGas() < cost) {
            return new OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
        }

        final var data = frame.readMemory(dataLocation, numBytes);

        final ImmutableList.Builder<LogTopic> builder = ImmutableList.builderWithExpectedSize(numTopics);
        for (int i = 0; i < numTopics; i++) {
            builder.add(LogTopic.create(leftPad(frame.popStackItem())));
        }

        // Since these are consumed by mirror nodes, which always want to know the Hedera id
        // of the emitting contract, we always resolve to a long-zero address for the log
        final var loggerAddress = longZeroAddressOfRecipient(frame);
        frame.addLog(new Log(loggerAddress, data, builder.build()));

        return new OperationResult(cost, null);
    }
}
