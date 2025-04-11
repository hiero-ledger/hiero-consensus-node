// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.incrementHederaGasUsage;

import java.util.Map;
import java.util.Optional;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.internal.OverflowException;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.operation.AddModOperation;
import org.hyperledger.besu.evm.operation.AddOperation;
import org.hyperledger.besu.evm.operation.AndOperation;
import org.hyperledger.besu.evm.operation.ByteOperation;
import org.hyperledger.besu.evm.operation.DivOperation;
import org.hyperledger.besu.evm.operation.DupOperation;
import org.hyperledger.besu.evm.operation.ExpOperation;
import org.hyperledger.besu.evm.operation.GtOperation;
import org.hyperledger.besu.evm.operation.InvalidOperation;
import org.hyperledger.besu.evm.operation.IsZeroOperation;
import org.hyperledger.besu.evm.operation.JumpDestOperation;
import org.hyperledger.besu.evm.operation.JumpOperation;
import org.hyperledger.besu.evm.operation.JumpiOperation;
import org.hyperledger.besu.evm.operation.LtOperation;
import org.hyperledger.besu.evm.operation.ModOperation;
import org.hyperledger.besu.evm.operation.MulModOperation;
import org.hyperledger.besu.evm.operation.MulOperation;
import org.hyperledger.besu.evm.operation.NotOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.operation.OrOperation;
import org.hyperledger.besu.evm.operation.PopOperation;
import org.hyperledger.besu.evm.operation.Push0Operation;
import org.hyperledger.besu.evm.operation.PushOperation;
import org.hyperledger.besu.evm.operation.SDivOperation;
import org.hyperledger.besu.evm.operation.SGtOperation;
import org.hyperledger.besu.evm.operation.SLtOperation;
import org.hyperledger.besu.evm.operation.SModOperation;
import org.hyperledger.besu.evm.operation.SignExtendOperation;
import org.hyperledger.besu.evm.operation.StopOperation;
import org.hyperledger.besu.evm.operation.SubOperation;
import org.hyperledger.besu.evm.operation.SwapOperation;
import org.hyperledger.besu.evm.operation.VirtualOperation;
import org.hyperledger.besu.evm.operation.XorOperation;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds support for calculating an alternate ops gas schedule tracking.
 */
public class HederaEVM extends EVM {
    private final OperationRegistry operations;
    private static final Logger LOG = LoggerFactory.getLogger(HederaEVM.class);
    private final GasCalculator gasCalculator;
    private final Operation endOfScriptStop;
    private final EvmSpecVersion evmSpecVersion;
    private final boolean enableShanghai;
    private final Map<Integer, Long> hederaGasSchedule;

    public HederaEVM(
            OperationRegistry operations,
            GasCalculator gasCalculator,
            EvmConfiguration evmConfiguration,
            EvmSpecVersion evmSpecVersion,
            Map<Integer, Long> hederaGasSchedule) {
        super(operations, gasCalculator, evmConfiguration, evmSpecVersion);
        this.operations = operations;
        this.gasCalculator = gasCalculator;
        this.endOfScriptStop = new VirtualOperation(new StopOperation(gasCalculator));
        this.evmSpecVersion = evmSpecVersion;
        this.enableShanghai = EvmSpecVersion.SHANGHAI.ordinal() <= evmSpecVersion.ordinal();
        this.hederaGasSchedule = hederaGasSchedule;
    }

    @Override
    public void runToHalt(MessageFrame frame, OperationTracer tracing) {
        this.evmSpecVersion.maybeWarnVersion();
        OperationTracer operationTracer = tracing == OperationTracer.NO_TRACING ? null : tracing;
        byte[] code = frame.getCode().getBytes().toArrayUnsafe();
        Operation[] operationArray = this.operations.getOperations();

        while (frame.getState() == State.CODE_EXECUTING) {
            int pc = frame.getPC();

            Operation currentOperation;
            int opcode;
            try {
                opcode = code[pc] & 255;
                currentOperation = operationArray[opcode];
            } catch (ArrayIndexOutOfBoundsException var15) {
                opcode = 0;
                currentOperation = this.endOfScriptStop;
            }

            frame.setCurrentOperation(currentOperation);
            if (operationTracer != null) {
                operationTracer.tracePreExecution(frame);
            }

            Operation.OperationResult result;
            try {
                result = switch (opcode) {
                    case 0 -> StopOperation.staticOperation(frame);
                    case 1 -> AddOperation.staticOperation(frame);
                    case 2 -> MulOperation.staticOperation(frame);
                    case 3 -> SubOperation.staticOperation(frame);
                    case 4 -> DivOperation.staticOperation(frame);
                    case 5 -> SDivOperation.staticOperation(frame);
                    case 6 -> ModOperation.staticOperation(frame);
                    case 7 -> SModOperation.staticOperation(frame);
                    case 8 -> AddModOperation.staticOperation(frame);
                    case 9 -> MulModOperation.staticOperation(frame);
                    case 10 -> ExpOperation.staticOperation(frame, this.gasCalculator);
                    case 11 -> SignExtendOperation.staticOperation(frame);
                    case 12, 13, 14, 15 -> InvalidOperation.INVALID_RESULT;
                    case 16 -> LtOperation.staticOperation(frame);
                    case 17 -> GtOperation.staticOperation(frame);
                    case 18 -> SLtOperation.staticOperation(frame);
                    case 19 -> SGtOperation.staticOperation(frame);
                    case 21 -> IsZeroOperation.staticOperation(frame);
                    case 22 -> AndOperation.staticOperation(frame);
                    case 23 -> OrOperation.staticOperation(frame);
                    case 24 -> XorOperation.staticOperation(frame);
                    case 25 -> NotOperation.staticOperation(frame);
                    case 26 -> ByteOperation.staticOperation(frame);
                    case 80 -> PopOperation.staticOperation(frame);
                    case 86 -> JumpOperation.staticOperation(frame);
                    case 87 -> JumpiOperation.staticOperation(frame);
                    case 91 -> JumpDestOperation.JUMPDEST_SUCCESS;
                    case 95 -> this.enableShanghai
                            ? Push0Operation.staticOperation(frame)
                            : InvalidOperation.INVALID_RESULT;
                    case 96,
                            97,
                            98,
                            99,
                            100,
                            101,
                            102,
                            103,
                            104,
                            105,
                            106,
                            107,
                            108,
                            109,
                            110,
                            111,
                            112,
                            113,
                            114,
                            115,
                            116,
                            117,
                            118,
                            119,
                            120,
                            121,
                            122,
                            123,
                            124,
                            125,
                            126,
                            127 -> PushOperation.staticOperation(frame, code, pc, opcode - 95);
                    case 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143 -> DupOperation
                            .staticOperation(frame, opcode - 127);
                    case 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159 -> SwapOperation
                            .staticOperation(frame, opcode - 143);
                    default -> {
                        frame.setCurrentOperation(currentOperation);
                        yield currentOperation.execute(frame, this);
                    }};
            } catch (OverflowException var13) {
                result = OVERFLOW_RESPONSE;
            } catch (UnderflowException var14) {
                result = UNDERFLOW_RESPONSE;
            }

            ExceptionalHaltReason haltReason = result.getHaltReason();
            if (haltReason != null) {
                LOG.trace("MessageFrame evaluation halted because of {}", haltReason);
                frame.setExceptionalHaltReason(Optional.of(haltReason));
                frame.setState(State.EXCEPTIONAL_HALT);
            } else if (frame.decrementRemainingGas(result.getGasCost()) < 0L) {
                frame.setExceptionalHaltReason(Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS));
                frame.setState(State.EXCEPTIONAL_HALT);
            } else {
                /*
                 ** Important:  This is the code that has been updated verses the parent class from Besu.
                 ** As the code is in a while loop it is difficult to isolate.  We will need to maintain these changes
                 ** against new versions of the EVM class.
                 */
                incrementHederaGasUsage(frame, hederaGasSchedule.getOrDefault(opcode, result.getGasCost()));
            }

            if (frame.getState() == State.CODE_EXECUTING) {
                int currentPC = frame.getPC();
                int opSize = result.getPcIncrement();
                frame.setPC(currentPC + opSize);
            }

            if (operationTracer != null) {
                operationTracer.tracePostExecution(frame, result);
            }
        }
    }
}
