// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.HEDERA_OPS_DURATION;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.THROTTLE_BY_OPS_DURATION;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.metrics.OpsDurationMetrics;
import com.hedera.node.app.service.contract.impl.exec.utils.HederaOpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.HederaEVM;
import com.hedera.node.app.service.contract.impl.hevm.HederaOpsDuration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.AddOperation;
import org.hyperledger.besu.evm.operation.MulOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.operation.PushOperation;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HederaEVMTest {

    @Mock
    MessageFrame frame;

    @Mock
    OperationTracer operationTracer;

    @Mock
    OperationRegistry operationRegistry;

    @Mock
    GasCalculator gasCalculator;

    @Mock
    EvmConfiguration evmConfiguration;

    @Mock
    EvmSpecVersion evmSpecVersion;

    @Mock
    HederaOpsDuration opsDuration;

    @Mock
    ContractMetrics contractMetrics;

    @Mock
    OpsDurationMetrics opsDurationMetrics;

    @Mock
    HederaOpsDurationCounter opsDurationCounter;

    @Test
    void testRunToHaltOpsDurationIncrement() {
        // Create bytecode with known operations
        byte[] bytecode = new byte[] {
            0x01, // ADD (3 + 4 = 7)
            0x60,
            0x02, // MUL (7 * 2 = 14)
            0x00 // STOP
        };

        Code code = CodeFactory.createCode(Bytes.wrap(bytecode), 0, false);
        Operation[] operations = new Operation[] {
            new AddOperation(gasCalculator), // ADD operation
            new PushOperation(1, gasCalculator),
            new MulOperation(gasCalculator) // MUL operation
        };

        long[] opsDurationValues = new long[] {
            0L, // Initial ops duration
            5L, // Duration for ADD operation
            10L, // Duration for MUL operation
        };

        final Deque<MessageFrame> messageFrameStack = new ArrayDeque<>();
        messageFrameStack.addFirst(frame);
        given(frame.getCode()).willReturn(code);
        given(frame.getMessageFrameStack()).willReturn(messageFrameStack);
        given(frame.getState()).willReturn(State.CODE_EXECUTING);

        given(operationRegistry.getOperations()).willReturn(operations);
        given(opsDuration.getOpsDuration()).willReturn(opsDurationValues);
        given(contractMetrics.opsDurationMetrics()).willReturn(opsDurationMetrics);
        given(frame.getContextVariable(HEDERA_OPS_DURATION)).willReturn(opsDurationCounter);
        given(frame.getContextVariable(THROTTLE_BY_OPS_DURATION, false)).willReturn(false);

        AtomicInteger pc = new AtomicInteger();
        when(frame.getPC()).thenAnswer(invocation -> {
            return pc.getAndIncrement();
        });

        when(frame.popStackItem()).thenAnswer(invocation -> {
            // Simulate stack pop for PUSH1 operations
            if (pc.get() == 0) {
                return Bytes.of(3); // For PUSH1 3
            } else if (pc.get() == 2) {
                return Bytes.of(2); // For PUSH1 2
            }
            return Bytes.EMPTY; // For other operations
        });

        when(frame.getState()).thenAnswer(invocation -> {
            if (pc.get() < 8) {
                return State.CODE_EXECUTING; // Before STOP
            } else {
                return State.CODE_SUCCESS; // After STOP
            }
        });

        // Create HederaEVM instance and execute bytecode
        HederaEVM evm = new HederaEVM(
                operationRegistry, gasCalculator, evmConfiguration, evmSpecVersion, opsDuration, contractMetrics);
        evm.runToHalt(frame, operationTracer);

        verify(opsDurationCounter, times(2)).incrementOpsDuration(0);
        verify(opsDurationCounter).incrementOpsDuration(5);
        verify(opsDurationCounter).incrementOpsDuration(10);
    }
}
