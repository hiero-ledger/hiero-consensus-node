// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.OPS_DURATION_COUNTER;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.streams.CallOperationType;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.HederaEVM;
import com.hedera.node.app.service.contract.impl.hevm.HederaOperationsRegistry;
import com.hedera.node.app.service.contract.impl.hevm.OpsDurationSchedule;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.hyperledger.besu.evm.gascalculator.LondonGasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.jspecify.annotations.NonNull;
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
        HederaOperationsRegistry.forVersion(EvmSpecVersion.SHANGHAI)
                .register(operationRegistry, new LondonGasCalculator(), BigInteger.ZERO, EvmConfiguration.DEFAULT);

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
        final var byteCode = new ByteCodeBuilder()
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
        return prepareTestFrame(byteCode, opsDurationCounter);
    }

    private MessageFrame prepareTestFrame(final String byteCode, final OpsDurationCounter opsDurationCounter) {
        final var code = new Code(Bytes.fromHexString(byteCode));
        WorldUpdater world = mock(WorldUpdater.class);
        Address address = randomAddress();
        MutableAccount mutableAccount = mock(MutableAccount.class);
        given(mutableAccount.getBalance()).willReturn(Wei.fromEth(1));
        given(world.getAccount(address)).willReturn(mutableAccount);
        Account account = mock(Account.class);
        given(account.getNonce()).willReturn(1L);
        given(account.getCode()).willReturn(Bytes.EMPTY);
        given(account.getBalance()).willReturn(Wei.fromEth(1));
        given(world.get(address)).willReturn(account);

        final var frame = MessageFrame.builder()
                .type(MessageFrame.Type.MESSAGE_CALL)
                .worldUpdater(world)
                .initialGas(10_000_000L)
                .address(address)
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
                .completer(_ -> {})
                .blockHashLookup((_, _) -> {
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
        return Address.fromHexString(HexFormat.of().formatHex(bytes));
    }

    public static class TestOpcodesTracer implements ActionSidecarContentTracer {

        private final Map<Integer, Long> opcodesGasMap = new HashMap<>();

        public long getOpcodeGas(int opcode) {
            return opcodesGasMap.getOrDefault(opcode, 0L);
        }

        @Override
        public void traceOriginAction(@NonNull MessageFrame frame) {}

        @Override
        public void sanitizeTracedActions(@NonNull MessageFrame frame) {}

        @Override
        public void tracePrecompileResult(@NonNull MessageFrame frame, @NonNull ContractActionType type) {}

        @Override
        public List<ContractAction> contractActions() {
            return List.of();
        }

        @Override
        public void tracePostExecution(MessageFrame frame, Operation.OperationResult operationResult) {
            Bytes code = frame.getCode().getBytes();
            int pc;
            if (frame.getState() == State.CODE_EXECUTING) {
                pc = frame.getPC() - operationResult.getPcIncrement();
            } else {
                pc = frame.getPC();
            }
            int opcode = 0;
            if (pc < code.size()) {
                opcode = code.get(pc) & 255;
            }
            opcodesGasMap.put(opcode, operationResult.getGasCost());
        }

        @Override
        public void tracePerOpcode(MessageFrame frame, long gas, ExceptionalHaltReason halt, Operation op) {}

        @Override
        public void traceSuspended(MessageFrame parent, MessageFrame child, CallOperationType opCall) {}

        @Override
        public void traceNotExecuting(MessageFrame child) {}
    }

    static List<Arguments> dynamicOpsDurationOpcodesTestParams() {
        // we are not testing RETURNDATACOPY 0x3E/62 because it requires a separate function deployment, and it is hard
        // to do this with plain bytecode.
        return List.of(
                // KECCAK256 0x20 / 32
                Arguments.of("KECCAK256", 32, "0x602060405180820160405220", "0x608060405180820160405220"),
                // CALLDATACOPY 0x37/55
                Arguments.of("CALLDATACOPY", 55, "0x6020600060405180830160405237", "0x6080600060405180830160405237"),
                // CODECOPY 0x39/57
                Arguments.of("CODECOPY", 57, "0x6020600060405180830160405239", "0x6080600060405180830160405239"),
                // EXTCODECOPY 0x3C/60
                Arguments.of("EXTCODECOPY", 60, "0x60206000604051808301604052303C", "0x60806000604051808301604052303C"),
                // LOG0 0xA0/160
                Arguments.of("LOG0", 160, "0x6020604051808201604052A0", "0x6080604051808201604052A0"),
                // LOG1 0xA1/161
                Arguments.of(
                        "LOG1",
                        161,
                        "0x7f11111111111111111111111111111111111111111111111111111111111111116020604051808201604052A1",
                        "0x7f11111111111111111111111111111111111111111111111111111111111111116080604051808201604052A1"),
                // LOG2 0xA2/162
                Arguments.of(
                        "LOG2",
                        162,
                        "0x7f22222222222222222222222222222222222222222222222222222222222222227f11111111111111111111111111111111111111111111111111111111111111116020604051808201604052A2",
                        "0x7f22222222222222222222222222222222222222222222222222222222222222227f11111111111111111111111111111111111111111111111111111111111111116080604051808201604052A2"),
                // LOG3 0xA3/163
                Arguments.of(
                        "LOG3",
                        163,
                        "0x7f33333333333333333333333333333333333333333333333333333333333333337f22222222222222222222222222222222222222222222222222222222222222227f11111111111111111111111111111111111111111111111111111111111111116020604051808201604052A3",
                        "0x7f33333333333333333333333333333333333333333333333333333333333333337f22222222222222222222222222222222222222222222222222222222222222227f11111111111111111111111111111111111111111111111111111111111111116080604051808201604052A3"),
                // LOG4 0xA4/164
                Arguments.of(
                        "LOG4",
                        164,
                        "0x7f44444444444444444444444444444444444444444444444444444444444444447f33333333333333333333333333333333333333333333333333333333333333337f22222222222222222222222222222222222222222222222222222222222222227f11111111111111111111111111111111111111111111111111111111111111116020604051808201604052A4",
                        "0x7f44444444444444444444444444444444444444444444444444444444444444447f33333333333333333333333333333333333333333333333333333333333333337f22222222222222222222222222222222222222222222222222222222222222227f11111111111111111111111111111111111111111111111111111111111111116080604051808201604052A4"),
                // CREATE 0xF0/240
                Arguments.of("CREATE", 240, "0x60206040518082016040526000F0", "0x60806040518082016040526000F0"),
                // CREATE2 0xF5/245
                Arguments.of(
                        "CREATE2", 245, "0x600060206040518082016040526000F5", "0x600060806040518082016040526000F5"),
                // RETURN 0xF3/243
                Arguments.of("RETURN", 243, "0x6020604051808201604052F3", "0x6080604051808201604052F3"),
                // REVERT 0xFD/253
                Arguments.of("REVERT", 253, "0x6020604051808201604052FD", "0x6080604051808201604052FD"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dynamicOpsDurationOpcodesTestParams")
    void dynamicOpsDurationOpcodesTest(
            final String name, final int opcode, final String bytecode32, final String bytecode128) {
        final var opsDurationSchedule = OpsDurationSchedule.fromConfig(TestHelpers.DEFAULT_OPS_DURATION_CONFIG);

        final var operationRegistry = new OperationRegistry();
        HederaOperationsRegistry.forVersion(EvmSpecVersion.SHANGHAI)
                .register(operationRegistry, new LondonGasCalculator(), BigInteger.ZERO, EvmConfiguration.DEFAULT);

        final var hederaEvm = new HederaEVM(
                operationRegistry,
                new LondonGasCalculator(),
                EvmConfiguration.DEFAULT,
                EvmSpecVersion.defaultVersion());

        // execute for length 32
        final var exactOpsDurationThrottle32 = OpsDurationCounter.withSchedule(opsDurationSchedule);
        final var exactFrame32 = prepareTestFrame(bytecode32, exactOpsDurationThrottle32);
        final var tracer32 = new TestOpcodesTracer();
        hederaEvm.runToHalt(exactFrame32, tracer32);
        // execute for length 128
        final var exactOpsDurationThrottle128 = OpsDurationCounter.withSchedule(opsDurationSchedule);
        final var exactFrame128 = prepareTestFrame(bytecode128, exactOpsDurationThrottle128);
        final var tracer128 = new TestOpcodesTracer();
        hederaEvm.runToHalt(exactFrame128, tracer128);

        final var gas32 = tracer32.getOpcodeGas(opcode);
        final var ops32 = gas32
                * opsDurationSchedule.opsGasBasedDurationMultiplier()
                / opsDurationSchedule.multipliersDenominator();
        final var gas128 = tracer128.getOpcodeGas(opcode);
        final var ops128 = gas128
                * opsDurationSchedule.opsGasBasedDurationMultiplier()
                / opsDurationSchedule.multipliersDenominator();
        assertTrue(
                gas128 > gas32,
                "Gas for length 32 = '%s' should be more than Gas for length 128 = '%s'".formatted(gas32, gas128));
        assertTrue(exactOpsDurationThrottle128.opsDurationUnitsConsumed()
                > exactOpsDurationThrottle32.opsDurationUnitsConsumed());
        // length 32 ops duration should differ from length 128 ops duration
        assertEquals(
                exactOpsDurationThrottle32.opsDurationUnitsConsumed() + ops128 - ops32,
                exactOpsDurationThrottle128.opsDurationUnitsConsumed(),
                "length 32 ops duration = '%s' should differ from length 128 ops duration = '%s' by exactly 'ops128=%s - ops32=%s' = %s"
                        .formatted(
                                exactOpsDurationThrottle32.opsDurationUnitsConsumed(),
                                exactOpsDurationThrottle128.opsDurationUnitsConsumed(),
                                ops128,
                                ops32,
                                ops128 - ops32));
    }
}
