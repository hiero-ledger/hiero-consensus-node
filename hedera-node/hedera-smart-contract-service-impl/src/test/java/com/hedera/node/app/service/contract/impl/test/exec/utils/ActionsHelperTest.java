// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.utils;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.streams.CallOperationType;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionsHelper;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionsHelperTest {
    @Mock
    private Operation operation;

    @Mock
    private MessageFrame frame;

    @Mock
    private ProxyWorldUpdater worldUpdater;

    private final ActionsHelper subject = new ActionsHelper();

    @Test
    void prettyPrintsAsExpected() {
        final var expected = "SolidityAction("
                + "callType: CALL, "
                + "callOperationType: OP_CALL, "
                + "value: 0, "
                + "gas: 500000, "
                + "gasUsed: 0, "
                + "callDepth: 0, "
                + "callingAccount: <null>, "
                + "callingContract: " + CALL_ACTION.callingContract() + ", "
                + "recipientAccount: <null>, "
                + "recipientContract: " + CALL_ACTION.recipientContract() + ", "
                + "invalidSolidityAddress (aka targetedAddress): <null>, "
                + "input: 010203040506070809, "
                + "output: 090807060504030201, "
                + "revertReason: <null>, "
                + "error: <null>)";
        final var actual = subject.prettyPrint(CALL_ACTION);
        assertEquals(expected, actual);
    }

    @Test
    void isValidWithAllFieldsSet() {
        assertTrue(subject.isValid(CALL_ACTION));
    }

    @Test
    void invalidWithMissingCallType() {
        assertFalse(subject.isValid(CALL_ACTION.copyBuilder().callType(null).build()));
        assertFalse(subject.isValid(
                CALL_ACTION.copyBuilder().callType(ContractActionType.NO_ACTION).build()));
    }

    @Test
    void invalidWithMissingCallOperationType() {
        assertFalse(subject.isValid(
                CALL_ACTION.copyBuilder().callOperationType(null).build()));
        assertFalse(subject.isValid(CALL_ACTION
                .copyBuilder()
                .callOperationType(CallOperationType.OP_UNKNOWN)
                .build()));
    }

    @Test
    void mustHaveCallingAccountOrContract() {
        assertFalse(subject.isValid(
                ContractAction.newBuilder().callType(ContractActionType.CALL).build()));
    }

    @Test
    void mustHaveNonNullInput() {
        assertFalse(subject.isValid(CALL_ACTION.copyBuilder().input(null).build()));
    }

    @Test
    void isNotRequiredToHaveRecipientAccountOrContractOrTargetedAddress() {
        assertTrue(subject.isValid(
                CALL_ACTION.copyBuilder().recipientContract((ContractID) null).build()));
        assertTrue(subject.isValid(CALL_ACTION
                .copyBuilder()
                .recipientContract((ContractID) null)
                .recipientAccount(AccountID.newBuilder().accountNum(123).build())
                .build()));
        assertTrue(subject.isValid(CALL_ACTION
                .copyBuilder()
                .recipientContract((ContractID) null)
                .targetedAddress(Bytes.wrap(new byte[20]))
                .build()));
    }

    @Test
    void representsCallToMissingAddressAsExpected() {
        given(frame.getRemainingGas()).willReturn(REMAINING_GAS);
        given(frame.getDepth()).willReturn(STACK_DEPTH);
        givenResolvableEvmAddress();
        given(operation.getOpcode()).willReturn(0xF1);
        given(frame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getCurrentOperation()).willReturn(operation);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        final var expectedAction = ContractAction.newBuilder()
                .callType(ContractActionType.CALL)
                .gas(REMAINING_GAS)
                .callDepth(STACK_DEPTH + 1)
                .callingContract(CALLED_CONTRACT_ID)
                .targetedAddress(tuweniToPbjBytes(NON_SYSTEM_LONG_ZERO_ADDRESS))
                .error(Bytes.wrap("INVALID_SOLIDITY_ADDRESS".getBytes()))
                .callOperationType(CallOperationType.OP_CALL)
                .build();
        final var actualAction = subject.createSynthActionForMissingAddressIn(frame, NON_SYSTEM_LONG_ZERO_ADDRESS);

        assertEquals(expectedAction, actualAction);
    }

    private void givenResolvableEvmAddress() {
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getHederaContractId(EIP_1014_ADDRESS)).willReturn(CALLED_CONTRACT_ID);
    }
}
