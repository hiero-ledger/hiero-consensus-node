// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.ERROR_DECODING_PRECOMPILE_INPUT;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CONFIG_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONTRACTS_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;
import static java.util.Collections.emptySet;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.DispatchForResponseCodeHssCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.deleteschedule.DeleteScheduleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.swirlds.config.api.Configuration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DispatchForResponseCodeHssCallTest extends CallAttemptTestBase {
    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private DispatchGasCalculator dispatchGasCalculator;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private Configuration configuration;

    private final Deque<MessageFrame> stack = new ArrayDeque<>();

    private DispatchForResponseCodeHssCall subject;
    private DispatchForResponseCodeHssCall subjectScheduleCreateResultEncoder;
    private DispatchForResponseCodeHssCall subjectFromAttempt;

    @BeforeEach
    void setUp() {
        subject = new DispatchForResponseCodeHssCall(
                mockEnhancement(),
                gasCalculator,
                AccountID.DEFAULT,
                TransactionBody.DEFAULT,
                verificationStrategy,
                dispatchGasCalculator,
                emptySet(),
                builder -> ReturnTypes.encodedRc(builder.status()));
        // create call with 'scheduleCreateResultEncode' encoder
        subjectScheduleCreateResultEncoder = new DispatchForResponseCodeHssCall(
                mockEnhancement(),
                gasCalculator,
                AccountID.DEFAULT,
                TransactionBody.DEFAULT,
                verificationStrategy,
                dispatchGasCalculator,
                emptySet(),
                DispatchForResponseCodeHssCall::scheduleCreateResultEncode);
        // create call from Attempt constructor
        DeleteScheduleTranslator translator =
                new DeleteScheduleTranslator(systemContractMethodRegistry, contractMetrics);
        HssCallAttempt attempt = createHssCallAttempt(
                Bytes.wrap(DeleteScheduleTranslator.DELETE_SCHEDULE.selector()),
                false,
                configuration,
                List.of(translator));
        given(addressIdConverter.convertSender(attempt.senderAddress())).willReturn(AccountID.DEFAULT);
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, false, nativeOperations))
                .willReturn(verificationStrategy);
        subjectFromAttempt =
                new DispatchForResponseCodeHssCall(attempt, TransactionBody.DEFAULT, dispatchGasCalculator, emptySet());
    }

    private byte[] successResult(final DispatchForResponseCodeHssCall call) {
        given(systemContractOperations.dispatch(
                        TransactionBody.DEFAULT,
                        verificationStrategy,
                        AccountID.DEFAULT,
                        ContractCallStreamBuilder.class,
                        Collections.emptySet(),
                        DispatchOptions.UsePresetTxnId.NO))
                .willReturn(recordBuilder);
        given(dispatchGasCalculator.gasRequirement(
                        TransactionBody.DEFAULT, gasCalculator, mockEnhancement(), AccountID.DEFAULT))
                .willReturn(123L);
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var pricedResult = call.execute(frame);
        final var contractResult = pricedResult.fullResult().result().getOutput();
        return contractResult.toArray();
    }

    @Test
    void successResult() {
        assertArrayEquals(ReturnTypes.encodedRc(SUCCESS).array(), successResult(subject));
    }

    @Test
    void successResultFromScheduleCreateResultEncoder() {
        given(recordBuilder.scheduleID()).willReturn(ScheduleID.newBuilder().build());
        assertArrayEquals(
                ReturnTypes.RC_AND_ADDRESS_ENCODER
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), ReturnTypes.ZERO_ADDRESS))
                        .array(),
                successResult(subjectScheduleCreateResultEncoder));
    }

    @Test
    void successResultFromAttemptConstructor() {
        assertArrayEquals(ReturnTypes.encodedRc(SUCCESS).array(), successResult(subjectFromAttempt));
    }

    @Test
    void haltsImmediatelyWithNullDispatch() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(DEFAULT_CONFIG);
        given(frame.getMessageFrameStack()).willReturn(stack);

        subject = new DispatchForResponseCodeHssCall(
                mockEnhancement(),
                gasCalculator,
                AccountID.DEFAULT,
                null,
                verificationStrategy,
                dispatchGasCalculator,
                emptySet(),
                builder -> ReturnTypes.encodedRc(builder.status()));

        final var pricedResult = subject.execute(frame);
        final var fullResult = pricedResult.fullResult();

        assertEquals(
                Optional.of(ERROR_DECODING_PRECOMPILE_INPUT),
                fullResult.result().getHaltReason());
        assertEquals(DEFAULT_CONTRACTS_CONFIG.precompileHtsDefaultGasCost(), fullResult.gasRequirement());
    }

    private byte[] failureResult(final DispatchForResponseCodeHssCall call) {
        given(systemContractOperations.dispatch(
                        TransactionBody.DEFAULT,
                        verificationStrategy,
                        AccountID.DEFAULT,
                        ContractCallStreamBuilder.class,
                        emptySet(),
                        DispatchOptions.UsePresetTxnId.NO))
                .willReturn(recordBuilder);
        given(dispatchGasCalculator.gasRequirement(
                        TransactionBody.DEFAULT, gasCalculator, mockEnhancement(), AccountID.DEFAULT))
                .willReturn(123L);
        given(recordBuilder.status()).willReturn(INVALID_SCHEDULE_ID);

        final var pricedResult = call.execute(frame);
        return pricedResult.fullResult().result().getOutput().toArray();
    }

    @Test
    void failureResult() {
        assertArrayEquals(ReturnTypes.encodedRc(INVALID_SCHEDULE_ID).array(), failureResult(subject));
    }

    @Test
    void failureResultFromScheduleCreateResultEncoder() {
        given(recordBuilder.scheduleID()).willReturn(ScheduleID.newBuilder().build());
        assertArrayEquals(
                ReturnTypes.RC_AND_ADDRESS_ENCODER
                        .encode(Tuple.of((long) INVALID_SCHEDULE_ID.protoOrdinal(), ReturnTypes.ZERO_ADDRESS))
                        .array(),
                failureResult(subjectScheduleCreateResultEncoder));
    }

    @Test
    void failureResultFromAttemptConstructor() {
        assertArrayEquals(ReturnTypes.encodedRc(INVALID_SCHEDULE_ID).array(), failureResult(subjectFromAttempt));
    }
}
