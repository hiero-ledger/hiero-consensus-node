// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.OPS_DURATION_COUNTER;
import static org.hyperledger.besu.evm.MainnetEVMs.registerShanghaiOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.HederaEVM;
import com.hedera.node.app.service.contract.impl.hevm.OpsDurationSchedule;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.bouncycastle.util.encoders.Hex;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.hyperledger.besu.evm.gascalculator.LondonGasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HederaEVMTest {
    private final Random random = new Random(12345);

    static List<Arguments> opsDurationThrottleTestParams() {
        return List.of(
                Arguments.of(1, 729),
                Arguments.of(100, 51021),
                Arguments.of(900, 457421),
                Arguments.of(50000, 25400221),
                Arguments.of(100000, 50800221));
    }

    @ParameterizedTest
    @MethodSource("opsDurationThrottleTestParams")
    void opsDurationThrottleTest(final int loopIterations, final long expectedOpsDurationUnitsConsumed) {
        final var opsDurationSchedule = OpsDurationSchedule.fromConfig(TestHelpers.DEFAULT_OPS_DURATION_CONFIG);

        final var operationRegistry = new OperationRegistry();
        registerShanghaiOperations(operationRegistry, new LondonGasCalculator(), BigInteger.ZERO);

        final var hederaEvm = new HederaEVM(
                operationRegistry,
                new LondonGasCalculator(),
                EvmConfiguration.DEFAULT,
                EvmSpecVersion.defaultVersion());

        final var exactOpsDurationThrottle = OpsDurationCounter.withSchedule(opsDurationSchedule);
        final var exactFrame = prepareTestFrame(loopIterations, exactOpsDurationThrottle);
        hederaEvm.runToHalt(exactFrame, OperationTracer.NO_TRACING);
        assertEquals(expectedOpsDurationUnitsConsumed, exactOpsDurationThrottle.opsDurationUnitsConsumed());
        assertTrue(exactFrame.getRevertReason().isEmpty());
    }

    private MessageFrame prepareTestFrame(final int loopIterations, final OpsDurationCounter opsDurationCounter) {
        final var byteCodeBuilder = new ByteCodeBuilder()
                .push32(UInt256.valueOf(loopIterations)) // Initialize the local var
                .jumpdest()
                .dup1()
                .conditionalJump(39) // Skip the stop below if we're still iterating
                .stop()
                .jumpdest()
                .push(1) // Subtract 1
                .swap1()
                .sub()
                .jump(33) // Loop
                .toString();

        final var code = TestHelpers.CODE_FACTORY.createCode(Bytes.fromHexString(byteCodeBuilder), false);

        final var frame = MessageFrame.builder()
                .type(MessageFrame.Type.MESSAGE_CALL)
                .worldUpdater(mock(WorldUpdater.class))
                .initialGas(10_000_000L)
                .address(randomAddress())
                .originator(randomAddress())
                .contract(randomAddress())
                .gasPrice(Wei.ONE)
                .blobGasPrice(Wei.ONE)
                .inputData(Bytes.EMPTY)
                .sender(randomAddress())
                .value(Wei.ZERO)
                .apparentValue(Wei.ZERO)
                .code(code)
                .blockValues(mock(BlockValues.class))
                .isStatic(false)
                .maxStackSize(100)
                .completer(unused -> {})
                .blockHashLookup((unusedFrame, unused) -> {
                    throw new IllegalStateException();
                })
                .contextVariables(Map.of(OPS_DURATION_COUNTER, opsDurationCounter))
                .miningBeneficiary(randomAddress())
                .build();
        frame.setState(State.CODE_EXECUTING);
        return frame;
    }

    private Address randomAddress() {
        final var bytes = new byte[20];
        random.nextBytes(bytes);
        return Address.fromHexString(Hex.toHexString(bytes));
    }
}
