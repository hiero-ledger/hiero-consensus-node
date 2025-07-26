// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;
import static org.hyperledger.besu.evm.internal.Words.clampedToLong;

import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.operation.AbstractOperation;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * Implementation assistance for Hedera-customized create operations. Specializations must override methods to:
 * <ol>
 *     <li>Check if the create operation is disabled.</li>
 *     <li>Compute the address for the new contract, and set up the create in the frame's {@link ProxyWorldUpdater}.</li>
 *     <li>Do any Hedera-specific completion work needed when the spawned {@code CONTRACT_CREATION} succeeds.</li>
 * </ol>
 *
 * <p>Note that unlike in Besu, it is possible for the second step that computes the new address to fail, since
 * a {@code CREATE2} could reference an existing hollow account with lazy creation disabled. So this class also does
 * a null-check after calling {@link AbstractCustomCreateOperation#setupPendingCreation(MessageFrame)}, and fails
 * as appropriate.
 */
public abstract class AbstractCustomCreateOperation extends AbstractOperation {
    private static final int MAX_STACK_DEPTH = 1024;
    private static final Operation.OperationResult INVALID_RESPONSE =
            new OperationResult(0L, ExceptionalHaltReason.INVALID_OPERATION);
    private static final Operation.OperationResult UNDERFLOW_RESPONSE =
            new Operation.OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
    private final CodeFactory codeFactory;

    protected AbstractCustomCreateOperation(
            final int opcode,
            @NonNull final String name,
            final int stackItemsConsumed,
            final int stackItemsProduced,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final CodeFactory codeFactory) {
        super(opcode, name, stackItemsConsumed, stackItemsProduced, gasCalculator);
        this.codeFactory = codeFactory;
    }

    /**
     * Returns true if this operation is enabled for the given frame.
     *
     * @param frame the frame running the operation
     * @return true if enabled
     */
    protected abstract boolean isEnabled(@NonNull MessageFrame frame);

    /**
     * Returns the gas cost of this operation for the given frame.
     *
     * @param frame the frame running the operation
     * @return the gas cost
     */
    protected abstract long cost(@NonNull MessageFrame frame);

    /**
     * Sets up the pending creation for this operation and returns the address to be used
     * as receiver of the child {@code CONTRACT_CREATION} message; or null if the creation
     * should be aborted.
     *
     * @param frame the frame running the operation (and possibly spawning the child CONTRACT_CREATION)
     * @return the address for the child message, or null if the creation should be aborted
     */
    protected abstract @Nullable Address setupPendingCreation(@NonNull final MessageFrame frame);

    /**
     * Called when the child {@code CONTRACT_CREATION} message has completed successfully,
     * used to give specializations the chance to do any Hedera-specific logic.
     *
     * @param frame the frame running the successful operation
     * @param createdAddress the address of the newly created contract
     */
    protected abstract void onSuccess(@NonNull MessageFrame frame, @NonNull Address createdAddress);

    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        if (!isEnabled(frame)) {
            return INVALID_RESPONSE;
        }
        if (frame.stackSize() < getStackItemsConsumed()) {
            return UNDERFLOW_RESPONSE;
        }
        final long cost = cost(frame);
        if (frame.isStatic()) {
            return new OperationResult(cost, ILLEGAL_STATE_CHANGE);
        }
        if (frame.getRemainingGas() < cost) {
            return new Operation.OperationResult(cost, INSUFFICIENT_GAS);
        }
        final var value = Wei.wrap(frame.getStackItem(0));
        final var account = frame.getWorldUpdater().getAccount(frame.getRecipientAddress());
        frame.clearReturnData();
        if (value.compareTo(account.getBalance()) > 0 || frame.getDepth() >= MAX_STACK_DEPTH) {
            fail(frame);
        } else {
            spawnChildMessage(frame);
        }
        return new Operation.OperationResult(cost, null);
    }

    private void spawnChildMessage(@NonNull final MessageFrame frame) {
        // Calculate memory cost prior to expansion
        final var cost = cost(frame);
        frame.decrementRemainingGas(cost);

        final var account = frame.getWorldUpdater().getAccount(frame.getRecipientAddress());
        account.incrementNonce();

        final var value = Wei.wrap(frame.getStackItem(0));
        final var inputOffset = clampedToLong(frame.getStackItem(1));
        final var inputSize = clampedToLong(frame.getStackItem(2));
        final var inputData = frame.readMemory(inputOffset, inputSize);
        final var contractAddress = setupPendingCreation(frame);

        if (contractAddress == null) {
            fail(frame);
            return;
        }

        final var childGasStipend = gasCalculator().gasAvailableForChildCreate(frame.getRemainingGas());
        frame.decrementRemainingGas(childGasStipend);
        // child frame is added to frame stack via build method
        MessageFrame.builder()
                .parentMessageFrame(frame)
                .type(MessageFrame.Type.CONTRACT_CREATION)
                .initialGas(childGasStipend)
                .address(contractAddress)
                .contract(contractAddress)
                .inputData(Bytes.EMPTY)
                .sender(frame.getRecipientAddress())
                .value(value)
                .apparentValue(value)
                .code(codeFactory.createCode(inputData, false))
                .completer(child -> complete(frame, child))
                .build();
        frame.incrementRemainingGas(cost);
        frame.setState(MessageFrame.State.CODE_SUSPENDED);
    }

    private void fail(@NonNull final MessageFrame frame) {
        final var inputOffset = clampedToLong(frame.getStackItem(1));
        final var inputSize = clampedToLong(frame.getStackItem(2));
        frame.readMutableMemory(inputOffset, inputSize);
        frame.popStackItems(getStackItemsConsumed());
        frame.pushStackItem(UInt256.ZERO);
    }

    private void complete(@NonNull final MessageFrame frame, @NonNull final MessageFrame childFrame) {
        frame.setState(MessageFrame.State.CODE_EXECUTING);
        frame.incrementRemainingGas(childFrame.getRemainingGas());
        frame.addLogs(childFrame.getLogs());
        frame.addCreates(childFrame.getCreates());
        frame.addSelfDestructs(childFrame.getSelfDestructs());
        frame.incrementGasRefund(childFrame.getGasRefund());
        frame.popStackItems(getStackItemsConsumed());
        if (childFrame.getState() == MessageFrame.State.COMPLETED_SUCCESS) {
            final var creation = childFrame.getContractAddress();
            frame.pushStackItem(Words.fromAddress(creation));
            onSuccess(frame, creation);
        } else {
            frame.setReturnData(childFrame.getOutputData());
            frame.pushStackItem(UInt256.ZERO);
        }
        final var currentPC = frame.getPC();
        frame.setPC(currentPC + 1);
    }
}
