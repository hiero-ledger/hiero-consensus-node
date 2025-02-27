// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.ERROR_DECODING_PRECOMPILE_INPUT;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall.OutputFn.STANDARD_OUTPUT_FN;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CONFIG_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONTRACTS_CONFIG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.spi.workflows.HandleException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DispatchForResponseCodeHtsCallTest extends CallTestBase {
    @Mock
    private DispatchForResponseCodeHtsCall.FailureCustomizer failureCustomizer;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private DispatchGasCalculator dispatchGasCalculator;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    private final Deque<MessageFrame> stack = new ArrayDeque<>();

    private DispatchForResponseCodeHtsCall subject;

    @BeforeEach
    void setUp() {
        subject = new DispatchForResponseCodeHtsCall(
                mockEnhancement(),
                gasCalculator,
                AccountID.DEFAULT,
                TransactionBody.DEFAULT,
                verificationStrategy,
                dispatchGasCalculator,
                failureCustomizer,
                STANDARD_OUTPUT_FN);
    }

    @Test
    void successResultNotCustomized() {
        given(systemContractOperations.dispatch(
                        TransactionBody.DEFAULT,
                        verificationStrategy,
                        AccountID.DEFAULT,
                        ContractCallStreamBuilder.class))
                .willReturn(recordBuilder);
        given(dispatchGasCalculator.gasRequirement(
                        TransactionBody.DEFAULT, gasCalculator, mockEnhancement(), AccountID.DEFAULT))
                .willReturn(123L);
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var pricedResult = subject.execute(frame);
        final var contractResult = pricedResult.fullResult().result().getOutput();
        assertArrayEquals(ReturnTypes.encodedRc(SUCCESS).array(), contractResult.toArray());

        verifyNoInteractions(failureCustomizer);
    }

    @Test
    void haltsImmediatelyWithNullDispatch() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(DEFAULT_CONFIG);
        given(frame.getMessageFrameStack()).willReturn(stack);

        subject = new DispatchForResponseCodeHtsCall(
                mockEnhancement(),
                gasCalculator,
                AccountID.DEFAULT,
                null,
                verificationStrategy,
                dispatchGasCalculator,
                failureCustomizer,
                STANDARD_OUTPUT_FN);

        final var pricedResult = subject.execute(frame);
        final var fullResult = pricedResult.fullResult();

        assertEquals(
                Optional.of(ERROR_DECODING_PRECOMPILE_INPUT),
                fullResult.result().getHaltReason());
        assertEquals(DEFAULT_CONTRACTS_CONFIG.precompileHtsDefaultGasCost(), fullResult.gasRequirement());
    }

    @Test
    void failureResultCustomized() {
        given(systemContractOperations.dispatch(
                        TransactionBody.DEFAULT,
                        verificationStrategy,
                        AccountID.DEFAULT,
                        ContractCallStreamBuilder.class))
                .willReturn(recordBuilder);
        given(dispatchGasCalculator.gasRequirement(
                        TransactionBody.DEFAULT, gasCalculator, mockEnhancement(), AccountID.DEFAULT))
                .willReturn(123L);
        given(recordBuilder.status()).willReturn(INVALID_ACCOUNT_ID).willReturn(INVALID_TREASURY_ACCOUNT_FOR_TOKEN);
        given(failureCustomizer.customize(TransactionBody.DEFAULT, INVALID_ACCOUNT_ID, mockEnhancement()))
                .willReturn(INVALID_TREASURY_ACCOUNT_FOR_TOKEN);

        final var pricedResult = subject.execute(frame);
        final var contractResult = pricedResult.fullResult().result().getOutput();
        assertArrayEquals(
                ReturnTypes.encodedRc(INVALID_TREASURY_ACCOUNT_FOR_TOKEN).array(), contractResult.toArray());
    }

    @Test
    void isSchedulableDispatchInFailsWithDefaultTxnBody() {
        // given
        subject = new DispatchForResponseCodeHtsCall(
                mockEnhancement(),
                gasCalculator,
                AccountID.DEFAULT,
                TransactionBody.DEFAULT,
                verificationStrategy,
                dispatchGasCalculator,
                failureCustomizer,
                STANDARD_OUTPUT_FN);

        // when/then
        assertThatExceptionOfType(HandleException.class)
                .isThrownBy(() -> subject.asSchedulableDispatchIn())
                .withMessage(INVALID_TRANSACTION.toString());
    }

    @Test
    void isSchedulableDispatchInFailsWithNullBody() {
        // given
        subject = new DispatchForResponseCodeHtsCall(
                mockEnhancement(),
                gasCalculator,
                AccountID.DEFAULT,
                null,
                verificationStrategy,
                dispatchGasCalculator,
                failureCustomizer,
                STANDARD_OUTPUT_FN);

        // when/then
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> subject.asSchedulableDispatchIn())
                .withMessage("Needs scheduleNative() support");
    }

    @Test
    void isSchedulableDispatchInHappyPath() {
        // given
        final var txnBody = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.DEFAULT)
                .build();
        final var expectedBody = SchedulableTransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.DEFAULT)
                .build();
        subject = new DispatchForResponseCodeHtsCall(
                mockEnhancement(),
                gasCalculator,
                AccountID.DEFAULT,
                txnBody,
                verificationStrategy,
                dispatchGasCalculator,
                failureCustomizer,
                STANDARD_OUTPUT_FN);
        // when/then
        final var response = subject.asSchedulableDispatchIn();
        assertEquals(expectedBody, response);
    }
}
