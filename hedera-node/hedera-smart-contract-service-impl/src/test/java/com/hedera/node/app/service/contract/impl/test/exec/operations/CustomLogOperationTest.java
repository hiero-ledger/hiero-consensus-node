// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.operations.CustomizedOpcodes.*;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static org.apache.tuweni.bytes.Bytes32.leftPad;
import static org.hyperledger.besu.evm.internal.Words.clampedToLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomLogOperation;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogTopic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomLogOperationTest {
    private static final Bytes LOG_DATA_LOCATION = Bytes.fromHexString("0xAAAAAA");
    private static final Bytes NUM_LOG_BYTES = Bytes.fromHexString("0xBBBBBB");
    private static final Bytes TOPIC_1 = Bytes.fromHexString("0xCCCC");
    private static final Bytes TOPIC_2 = Bytes.fromHexString("0xDDDD");
    private static final Bytes TOPIC_3 = Bytes.fromHexString("0xEEEE");
    private static final Bytes TOPIC_4 = Bytes.fromHexString("0xFFFF");
    private static final Bytes[] TOPICS = new Bytes[] {TOPIC_1, TOPIC_2, TOPIC_3, TOPIC_4};
    private static final long GAS_COST = 1234L;

    @Mock
    private ProxyWorldUpdater worldUpdater;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private MessageFrame frame;

    @Mock
    private EVM evm;

    @Test
    void onlyFiveLogOpcodesExist() {
        final var log0 = new CustomLogOperation(0, gasCalculator);
        final var log1 = new CustomLogOperation(1, gasCalculator);
        final var log2 = new CustomLogOperation(2, gasCalculator);
        final var log3 = new CustomLogOperation(3, gasCalculator);
        final var log4 = new CustomLogOperation(4, gasCalculator);
        assertThrows(IllegalArgumentException.class, () -> new CustomLogOperation(5, gasCalculator));
        assertEquals(LOG0.opcode(), log0.getOpcode());
        assertEquals(LOG1.opcode(), log1.getOpcode());
        assertEquals(LOG2.opcode(), log2.getOpcode());
        assertEquals(LOG3.opcode(), log3.getOpcode());
        assertEquals(LOG4.opcode(), log4.getOpcode());
    }

    @Test
    void staticFramesCannotLog() {
        final List<Bytes> extraPoppedItems = new ArrayList<>(List.of(NUM_LOG_BYTES));
        extraPoppedItems.addAll(Arrays.asList(TOPICS).subList(0, 2));
        given(frame.popStackItem()).willReturn(LOG_DATA_LOCATION, extraPoppedItems.toArray(Bytes[]::new));
        given(gasCalculator.logOperationGasCost(
                        frame, clampedToLong(LOG_DATA_LOCATION), clampedToLong(NUM_LOG_BYTES), 2))
                .willReturn(GAS_COST);
        given(frame.isStatic()).willReturn(true);

        final var subject = new CustomLogOperation(2, gasCalculator);
        final var result = subject.execute(frame, evm);

        assertEquals(ExceptionalHaltReason.ILLEGAL_STATE_CHANGE, result.getHaltReason());
    }

    @Test
    void requiresAdequateGas() {
        final List<Bytes> extraPoppedItems = new ArrayList<>(List.of(NUM_LOG_BYTES));
        extraPoppedItems.addAll(Arrays.asList(TOPICS).subList(0, 2));
        given(frame.popStackItem()).willReturn(LOG_DATA_LOCATION, extraPoppedItems.toArray(Bytes[]::new));
        given(gasCalculator.logOperationGasCost(
                        frame, clampedToLong(LOG_DATA_LOCATION), clampedToLong(NUM_LOG_BYTES), 2))
                .willReturn(GAS_COST);
        given(frame.getRemainingGas()).willReturn(GAS_COST - 1);

        final var subject = new CustomLogOperation(2, gasCalculator);
        final var result = subject.execute(frame, evm);

        assertEquals(ExceptionalHaltReason.INSUFFICIENT_GAS, result.getHaltReason());
    }

    @Test
    void addsExpectedLogWithResolvedLongZeroAddress() {
        givenHappyPathFrame(3);
        given(frame.getRecipientAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getHederaContractId(EIP_1014_ADDRESS)).willReturn(TestHelpers.CALLED_CONTRACT_ID);
        final var captor = ArgumentCaptor.forClass(Log.class);

        final ImmutableList.Builder<LogTopic> builder = ImmutableList.builderWithExpectedSize(3);
        for (int i = 0; i < 3; i++) {
            builder.add(LogTopic.create(leftPad(TOPICS[i])));
        }
        final var mirrorAddress = asLongZeroAddress(CALLED_CONTRACT_ID.contractNumOrThrow());
        final var expectedLog = new Log(mirrorAddress, pbjToTuweniBytes(TestHelpers.LOG_DATA), builder.build());

        final var subject = new CustomLogOperation(3, gasCalculator);
        final var result = subject.execute(frame, evm);

        assertNull(result.getHaltReason());
        verify(frame).addLog(captor.capture());
        assertEquals(expectedLog, captor.getValue());
    }

    @Test
    void addsExpectedLogWithAlreadyLongZeroAddress() {
        givenHappyPathFrame(2);
        given(frame.getRecipientAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var captor = ArgumentCaptor.forClass(Log.class);

        final ImmutableList.Builder<LogTopic> builder = ImmutableList.builderWithExpectedSize(2);
        for (int i = 0; i < 2; i++) {
            builder.add(LogTopic.create(leftPad(TOPICS[i])));
        }
        final var expectedLog =
                new Log(NON_SYSTEM_LONG_ZERO_ADDRESS, pbjToTuweniBytes(TestHelpers.LOG_DATA), builder.build());

        final var subject = new CustomLogOperation(2, gasCalculator);
        final var result = subject.execute(frame, evm);

        assertNull(result.getHaltReason());
        verify(frame).addLog(captor.capture());
        assertEquals(expectedLog, captor.getValue());
    }

    private void givenHappyPathFrame(final int numTopics) {
        final List<Bytes> extraPoppedItems = new ArrayList<>(List.of(NUM_LOG_BYTES));
        extraPoppedItems.addAll(Arrays.asList(TOPICS).subList(0, numTopics));
        given(frame.popStackItem()).willReturn(LOG_DATA_LOCATION, extraPoppedItems.toArray(Bytes[]::new));
        given(frame.getRemainingGas()).willReturn(GAS_COST * 2);
        given(gasCalculator.logOperationGasCost(
                        frame, clampedToLong(LOG_DATA_LOCATION), clampedToLong(NUM_LOG_BYTES), numTopics))
                .willReturn(GAS_COST);
        given(frame.readMemory(clampedToLong(LOG_DATA_LOCATION), clampedToLong(NUM_LOG_BYTES)))
                .willReturn(pbjToTuweniBytes(TestHelpers.LOG_DATA));
    }
}
