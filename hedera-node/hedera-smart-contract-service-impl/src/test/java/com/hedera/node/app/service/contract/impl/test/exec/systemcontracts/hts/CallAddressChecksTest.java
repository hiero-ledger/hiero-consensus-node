// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAddressChecks;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import java.util.ArrayDeque;
import java.util.Deque;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CallAddressChecksTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private MessageFrame parentFrame;

    private Deque<MessageFrame> stack = new ArrayDeque<>();

    private final CallAddressChecks subject = new CallAddressChecks();

    @Test
    void detectsParentDelegateCall() {
        givenParentFrame();
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(parentFrame.getContractAddress()).willReturn(TestHelpers.EIP_1014_ADDRESS);
        given(parentFrame.getRecipientAddress()).willReturn(TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS);
        assertTrue(subject.hasParentDelegateCall(frame));
    }

    void givenParentFrame() {
        stack.push(parentFrame);
        stack.push(frame);
    }
}
