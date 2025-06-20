// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.operations;

import static com.hedera.hapi.streams.SidecarType.CONTRACT_STATE_CHANGE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomSLoadOperation;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import java.util.Deque;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.SLoadOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomSLoadOperationTest {
    private static final Bytes A_STORAGE_KEY = Bytes32.fromHexString("0x1234");
    private static final Bytes A_STORAGE_VALUE = Bytes32.fromHexString("0x5678");

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private SLoadOperation delegate;

    @Mock
    private EVM evm;

    @Mock
    private MessageFrame frame;

    @Mock
    private Deque<MessageFrame> stack;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private ProxyWorldUpdater proxyWorldUpdater;

    @Mock
    private StorageAccessTracker accessTracker;

    private SLoadOperation realSLoadOperation;

    private CustomSLoadOperation subject;

    @BeforeEach
    void setUp() {
        realSLoadOperation = new SLoadOperation(gasCalculator);
        subject = new CustomSLoadOperation(featureFlags, delegate);
    }

    @Test
    void propagatesUnsuccessfulResults() {
        final var haltResult = new Operation.OperationResult(123, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
        given(delegate.execute(frame, evm)).willReturn(haltResult);

        final var result = subject.execute(frame, evm);

        assertSame(haltResult, result);
    }

    @Test
    void doesNoTrackingIfSidecarDisabled() {
        final var successResult = new Operation.OperationResult(123, null);

        given(frame.getStackItem(0)).willReturn(A_STORAGE_KEY);
        given(delegate.execute(frame, evm)).willReturn(successResult);

        final var result = subject.execute(frame, evm);

        assertSame(successResult, result);

        verify(featureFlags).isSidecarEnabled(frame, CONTRACT_STATE_CHANGE);
        verifyNoMoreInteractions(frame);
    }

    @Test
    void tracksReadValueOnSuccess() {
        final var successResult = new Operation.OperationResult(123, null);

        given(featureFlags.isSidecarEnabled(frame, CONTRACT_STATE_CHANGE)).willReturn(true);
        given(frame.getContextVariable(FrameUtils.TRACKER_CONTEXT_VARIABLE)).willReturn(accessTracker);
        given(frame.getStackItem(0)).willReturn(A_STORAGE_KEY).willReturn(A_STORAGE_VALUE);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        given(proxyWorldUpdater.getHederaContractId(EIP_1014_ADDRESS)).willReturn(CALLED_CONTRACT_ID);
        given(delegate.execute(frame, evm)).willReturn(successResult);
        given(frame.getRecipientAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);

        final var result = subject.execute(frame, evm);

        assertSame(successResult, result);
        verify(accessTracker)
                .trackIfFirstRead(
                        CALLED_CONTRACT_ID, UInt256.fromBytes(A_STORAGE_KEY), UInt256.fromBytes(A_STORAGE_VALUE));
    }

    @Test
    void worksAroundUnexpectedlyMissingAccessTracker() {
        final var successResult = new Operation.OperationResult(123, null);

        given(featureFlags.isSidecarEnabled(frame, CONTRACT_STATE_CHANGE)).willReturn(true);
        given(frame.getStackItem(0)).willReturn(A_STORAGE_KEY).willReturn(A_STORAGE_VALUE);
        given(delegate.execute(frame, evm)).willReturn(successResult);
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);

        final var result = subject.execute(frame, evm);

        assertSame(successResult, result);
    }

    @Test
    void delegatesOpcode() {
        given(delegate.getOpcode()).willReturn(realSLoadOperation.getOpcode());
        assertEquals(realSLoadOperation.getOpcode(), subject.getOpcode());
    }

    @Test
    void delegatesName() {
        given(delegate.getName()).willReturn(realSLoadOperation.getName());
        assertEquals(realSLoadOperation.getName(), subject.getName());
    }

    @Test
    void delegatesStackItemsConsumed() {
        given(delegate.getStackItemsConsumed()).willReturn(realSLoadOperation.getStackItemsConsumed());
        assertEquals(realSLoadOperation.getStackItemsConsumed(), subject.getStackItemsConsumed());
    }

    @Test
    void delegatesStackItemsProduced() {
        given(delegate.getStackItemsProduced()).willReturn(realSLoadOperation.getStackItemsProduced());
        assertEquals(realSLoadOperation.getStackItemsProduced(), subject.getStackItemsProduced());
    }
}
