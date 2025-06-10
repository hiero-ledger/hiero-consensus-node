// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.setapproval;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNAUTHORIZED_SPENDER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asBytesResult;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.readableRevertReason;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.setapproval.SetApprovalForAllCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.setapproval.SetApprovalForAllTranslator;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.hyperledger.besu.evm.precompile.PrecompiledContract.PrecompileContractResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SetApprovalForAllCallTest extends CallTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private ProxyWorldUpdater worldUpdater;

    private SetApprovalForAllCall subject;

    @BeforeEach
    void setup() {
        final Tuple tuple =
                Tuple.of(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, UNAUTHORIZED_SPENDER_HEADLONG_ADDRESS, Boolean.TRUE);
        final byte[] inputBytes = Bytes.wrapByteBuffer(
                        SetApprovalForAllTranslator.SET_APPROVAL_FOR_ALL.encodeCall(tuple))
                .toArray();

        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.addressIdConverter().convertSender(any())).willReturn(OWNER_ID);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.inputBytes()).willReturn(inputBytes);

        subject = new SetApprovalForAllCall(
                attempt, TransactionBody.newBuilder().build(), SetApprovalForAllTranslator::gasRequirement, false);

        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(recordBuilder);
    }

    @Test
    void setApprovalForAllCall_works() {
        // Given
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);

        // When
        final var result = subject.execute(frame).fullResult().result();

        // Then
        verifyResultStatus(result, ResponseCodeEnum.SUCCESS);
    }

    @Test
    void setApprovalForAllCallBadStatus_reverts() {
        // Given
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.ACCOUNT_DELETED);

        // When
        final var result = subject.execute(frame).fullResult().result();

        // Then
        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(readableRevertReason(ResponseCodeEnum.ACCOUNT_DELETED), result.getOutput());
    }

    @Test
    void setApprovalForAllCallInvalidToken_success() {
        // Given
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.INVALID_TOKEN_ID);

        // When
        final var result = subject.execute(frame).fullResult().result();

        // Then
        verifyResultStatus(result, ResponseCodeEnum.INVALID_TOKEN_ID);
    }

    @Test
    void setApprovalForAllCallInvalidAccount_success() {
        // Given
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.INVALID_ALLOWANCE_SPENDER_ID);

        // When
        final var result = subject.execute(frame).fullResult().result();

        // Then
        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        verifyResultStatus(result, ResponseCodeEnum.INVALID_ALLOWANCE_SPENDER_ID);
    }

    private static void verifyResultStatus(
            final PrecompileContractResult result, final ResponseCodeEnum expectedStatus) {
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                asBytesResult(SetApprovalForAllTranslator.SET_APPROVAL_FOR_ALL
                        .getOutputs()
                        .encode(Tuple.singleton(BigInteger.valueOf(expectedStatus.protoOrdinal())))),
                result.getOutput());
    }
}
