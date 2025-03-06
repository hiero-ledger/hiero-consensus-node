// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.schedulenative;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasPlus;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.RC_AND_ADDRESS_ENCODER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_SCHEDULE_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.TRANSACTION_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.verify;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulenative.ScheduleNativeCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallFactory;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.spi.workflows.DispatchOptions.UsePresetTxnId;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleNativeCallTest extends CallTestBase {

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private HederaWorldUpdater.Enhancement enhancement;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private MessageFrame frame;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private HtsCallFactory htsCallFactory;

    @Mock
    private HtsCallAttempt nativeAttempt;

    @Mock
    private DispatchForResponseCodeHtsCall nativeCall;

    private ScheduleNativeCall subject;

    @BeforeEach
    void setUp() {
        prepareCall();
        //        mockSystemOperations();
    }

    @Test
    void executesSuccessfully() {
        // given
        given(systemContractOperations.dispatch(any(), any(), any(), any(), any(), any()))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        given(recordBuilder.scheduleID()).willReturn(CALLED_SCHEDULE_ID);
        given(htsCallFactory.createCallAttemptFrom(any(), any(), any(), any())).willReturn(nativeAttempt);
        given(nativeAttempt.asExecutableCall()).willReturn(nativeCall);
        given(nativeCall.asSchedulableDispatchIn())
                .willReturn(SchedulableTransactionBody.newBuilder().build());

        // when
        final var result = subject.execute(frame).fullResult();

        // then
        final var encodedResponse = RC_AND_ADDRESS_ENCODER.encode(
                Tuple.of((long) SUCCESS.protoOrdinal(), headlongAddressOf(CALLED_SCHEDULE_ID)));
        final var expectedOutput = gasPlus(successResult(encodedResponse, 100L, recordBuilder), SUCCESS, false, 1100L)
                .fullResult();
        assertEquals(State.COMPLETED_SUCCESS, result.result().getState());
        assertEquals(expectedOutput.result().getOutput(), result.result().getOutput());
    }

    @Test
    void handlesFailureResponse() {
        // given
        final var failureStatus = ResponseCodeEnum.INVALID_SCHEDULE_ID;
        given(systemContractOperations.dispatch(any(), any(), any(), any(), any(), any()))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(failureStatus);
        given(htsCallFactory.createCallAttemptFrom(any(), any(), any(), any())).willReturn(nativeAttempt);
        given(nativeAttempt.asExecutableCall()).willReturn(nativeCall);
        given(nativeCall.asSchedulableDispatchIn())
                .willReturn(SchedulableTransactionBody.newBuilder().build());

        // when
        final var result = subject.execute(frame).fullResult();

        // then
        assertEquals(State.REVERT, result.result().getState());
        verify(recordBuilder, never()).scheduleID();
    }

    @Test
    void throwsWhenNativeCallIsNull() {
        // given
        given(htsCallFactory.createCallAttemptFrom(any(), any(), any(), any())).willReturn(nativeAttempt);
        given(nativeAttempt.asExecutableCall()).willReturn(null);

        // when/then
        assertThrows(NullPointerException.class, () -> subject.execute(frame));
    }

    @Test
    void usesCorrectDispatchOptions() {
        // given
        given(systemContractOperations.dispatch(any(), any(), any(), any(), any(), any()))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        given(htsCallFactory.createCallAttemptFrom(any(), any(), any(), any())).willReturn(nativeAttempt);
        given(nativeAttempt.asExecutableCall()).willReturn(nativeCall);
        given(nativeCall.asSchedulableDispatchIn())
                .willReturn(SchedulableTransactionBody.newBuilder().build());
        given(recordBuilder.scheduleID()).willReturn(CALLED_SCHEDULE_ID);

        // when
        subject.execute(frame);

        // then
        verify(systemContractOperations)
                .dispatch(
                        any(),
                        eq(verificationStrategy),
                        eq(SENDER_ID),
                        eq(ContractCallStreamBuilder.class),
                        eq(Set.of()),
                        eq(UsePresetTxnId.YES));
    }

    private void prepareCall() {
        subject = new ScheduleNativeCall(
                HTS_167_CONTRACT_ID,
                gasCalculator,
                mockEnhancement(),
                verificationStrategy,
                SENDER_ID,
                (body, calc, enh, payer) -> 100L,
                Set.of(),
                Bytes.of(),
                htsCallFactory,
                false);
    }

    private void mockSystemOperations() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(enhancement.systemOperations()).willReturn(systemContractOperations);
        given(nativeOperations.getTransactionID()).willReturn(TRANSACTION_ID);
    }
}
