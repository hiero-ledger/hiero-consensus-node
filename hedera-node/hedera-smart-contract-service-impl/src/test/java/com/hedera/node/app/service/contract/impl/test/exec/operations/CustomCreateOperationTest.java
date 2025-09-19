// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.operations;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CODE_FACTORY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSameResult;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.contract.impl.exec.operations.CustomCreateOperation;
import java.lang.reflect.Field;
import java.util.Deque;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.collections.undo.UndoScalar;
import org.hyperledger.besu.collections.undo.UndoSet;
import org.hyperledger.besu.collections.undo.UndoTable;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.TxValues;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

class CustomCreateOperationTest extends CreateOperationTestBase {
    private static final Address EXPECTED_CREATE1_ADDRESS = Address.contractAddress(RECIEVER_ADDRESS, NONCE - 1);

    @Mock
    private WorldUpdater updater;

    @Mock
    private TxValues txValues;

    @Mock
    private UndoTable<Address, Bytes32, Bytes32> undoTable;

    @Mock
    private Deque<MessageFrame> messageFrameStack;

    @Mock
    private UndoSet<Address> warmedUpAddresses;

    private CustomCreateOperation subject;

    @BeforeEach
    void setUp() {
        subject = new CustomCreateOperation(gasCalculator, CODE_FACTORY);
    }

    @Test
    void returnsUnderflowWhenStackSizeTooSmall() {
        given(frame.stackSize()).willReturn(2);

        final var expected = new Operation.OperationResult(0, INSUFFICIENT_STACK_ITEMS);

        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void haltsOnStaticFrame() {
        given(frame.stackSize()).willReturn(3);
        given(frame.isStatic()).willReturn(true);
        givenGasCostPrereqs();

        final var expected = new Operation.OperationResult(GAS_COST, ILLEGAL_STATE_CHANGE);

        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void haltsOnInsufficientGas() {
        given(frame.stackSize()).willReturn(3);
        given(frame.getRemainingGas()).willReturn(GAS_COST - 1);
        givenGasCostPrereqs();

        final var expected = new Operation.OperationResult(GAS_COST, INSUFFICIENT_GAS);

        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void failsWithInsufficientRecipientBalanceForValue() {
        given(frame.stackSize()).willReturn(3);
        given(frame.getStackItem(anyInt())).willReturn(Bytes.ofUnsignedLong(1));
        given(frame.getRemainingGas()).willReturn(GAS_COST);
        given(frame.getStackItem(0)).willReturn(Bytes.ofUnsignedLong(VALUE));
        given(frame.getRecipientAddress()).willReturn(RECIEVER_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getAccount(RECIEVER_ADDRESS)).willReturn(receiver);
        given(receiver.getBalance()).willReturn(Wei.ONE);
        givenGasCostPrereqs();

        final var expected = new Operation.OperationResult(GAS_COST, null);

        assertSameResult(expected, subject.execute(frame, evm));
        verify(frame).pushStackItem(UInt256.ZERO);
    }

    @Test
    void failsWithExcessStackDepth() {
        givenSpawnPrereqs();
        given(frame.getDepth()).willReturn(1024);
        givenGasCostPrereqs();

        final var expected = new Operation.OperationResult(GAS_COST, null);
        assertSameResult(expected, subject.execute(frame, evm));
        verify(frame).pushStackItem(UInt256.ZERO);
    }

    @Test
    void hasExpectedChildCompletionOnSuccess() throws IllegalAccessException, NoSuchFieldException {
        final var frameCaptor = ArgumentCaptor.forClass(MessageFrame.class);
        givenSpawnPrereqs();
        given(receiver.getNonce()).willReturn(NONCE);
        givenGasCostPrereqs();
        given(frame.readMemory(anyLong(), anyLong())).willReturn(INITCODE);

        given(txValues.transientStorage()).willReturn(undoTable);
        given(txValues.messageFrameStack()).willReturn(messageFrameStack);
        given(txValues.warmedUpAddresses()).willReturn(warmedUpAddresses);
        given(txValues.maxStackSize()).willReturn(1024);
        given(txValues.gasRefunds()).willReturn(new UndoScalar<>(1L));
        given(undoTable.mark()).willReturn(1L);

        final Field worldUdaterField = MessageFrame.class.getDeclaredField("worldUpdater");
        worldUdaterField.setAccessible(true);
        worldUdaterField.set(frame, updater);

        final Field txValuesField = MessageFrame.class.getDeclaredField("txValues");
        txValuesField.setAccessible(true);
        txValuesField.set(frame, txValues);

        final var expected = new Operation.OperationResult(GAS_COST, null);
        assertSameResult(expected, subject.execute(frame, evm));

        verify(messageFrameStack).addFirst(frameCaptor.capture());
        final var childFrame = frameCaptor.getValue();
        childFrame.setState(MessageFrame.State.COMPLETED_SUCCESS);
        childFrame.notifyCompletion();
        verify(worldUpdater).setupInternalAliasedCreate(RECIEVER_ADDRESS, EXPECTED_CREATE1_ADDRESS);
        verify(frame).pushStackItem(Words.fromAddress(EXPECTED_CREATE1_ADDRESS));
    }

    @Test
    void hasExpectedChildCompletionOnFailure() throws NoSuchFieldException, IllegalAccessException {
        final var captor = ArgumentCaptor.forClass(MessageFrame.class);
        givenSpawnPrereqs();
        given(frame.readMemory(anyLong(), anyLong())).willReturn(INITCODE);
        givenGasCostPrereqs();

        given(txValues.transientStorage()).willReturn(undoTable);
        given(txValues.messageFrameStack()).willReturn(messageFrameStack);
        given(txValues.warmedUpAddresses()).willReturn(warmedUpAddresses);
        given(txValues.maxStackSize()).willReturn(1024);
        given(txValues.gasRefunds()).willReturn(new UndoScalar<>(1L));
        given(undoTable.mark()).willReturn(1L);

        final Field worldUdaterField = MessageFrame.class.getDeclaredField("worldUpdater");
        worldUdaterField.setAccessible(true);
        worldUdaterField.set(frame, updater);

        final Field txValuesField = MessageFrame.class.getDeclaredField("txValues");
        txValuesField.setAccessible(true);
        txValuesField.set(frame, txValues);

        final var expected = new Operation.OperationResult(GAS_COST, null);
        assertSameResult(expected, subject.execute(frame, evm));

        verify(messageFrameStack).addFirst(captor.capture());
        final var childFrame = captor.getValue();
        // when:
        childFrame.setState(MessageFrame.State.COMPLETED_FAILED);
        childFrame.notifyCompletion();
        verify(frame).pushStackItem(UInt256.ZERO);
    }
}
