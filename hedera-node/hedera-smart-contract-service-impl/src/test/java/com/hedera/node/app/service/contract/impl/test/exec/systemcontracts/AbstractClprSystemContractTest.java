// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.HAPI_RECORD_BUILDER_CONTEXT_VARIABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getchannel.GetChannelTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getendpointmanifest.GetEndpointManifestTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify.VerifyBundleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify.VerifyConfigTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractClprSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CallType;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import java.util.ArrayDeque;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AbstractClprSystemContractTest {
    private static final long GAS_REQUIREMENT = 5_000L;
    private static final long PRICED_GAS_REQUIREMENT = 10_000L;

    private final TestClprContract subject = new TestClprContract();

    @Mock
    private Call call;

    @Mock
    private MessageFrame frame;

    @Mock
    private AbstractCallAttempt<?> attempt;

    @Mock
    private HederaOperations operations;

    @Mock
    private ProxyWorldUpdater updater;

    @BeforeEach
    void givenSuccessfulCall() {
        given(call.execute(frame))
                .willReturn(gasOnly(FullResult.successResult(Bytes.EMPTY, GAS_REQUIREMENT), SUCCESS, true));
        lenient().when(frame.getMessageFrameStack()).thenReturn(new ArrayDeque<>());
    }

    static Stream<Arguments> feeTargets() {
        return Stream.of(
                Arguments.of(VerifyConfigTranslator.VERIFY_CONFIG, 100_000_000L),
                Arguments.of(VerifyBundleTranslator.VERIFY_BUNDLE, 200_000_000L),
                Arguments.of(GetChannelTranslator.GET_CHANNEL_QUEUE_STATE, 200_000_000L),
                Arguments.of(GetEndpointManifestTranslator.GET_ENDPOINT_MANIFEST, 200_000_000L));
    }

    @ParameterizedTest
    @MethodSource("feeTargets")
    void pricesSuccessfulCallEntirelyThroughGas(final SystemContractMethod method, final long target) {
        givenPaidCall(target);

        final var result = subject.price(attempt, call, method, frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().gasRequirement()).isEqualTo(PRICED_GAS_REQUIREMENT);
        assertThat(result.nonGasCost()).isZero();
        verify(frame, never()).getValue();
        verifyNoInteractions(updater);
    }

    @Test
    void onChainStaticReadPaysThroughGasWithoutValue() {
        givenPaidCall(200_000_000L);
        given(frame.isStatic()).willReturn(true);
        assertThat(frame.isStatic()).isTrue();

        final var result = subject.price(attempt, call, GetChannelTranslator.GET_CHANNEL_QUEUE_STATE, frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().gasRequirement()).isEqualTo(PRICED_GAS_REQUIREMENT);
        assertThat(result.nonGasCost()).isZero();
        verify(frame, never()).getValue();
        verifyNoInteractions(updater);
    }

    @Test
    void onChainDelegateReadPaysThroughGasWithoutDebitingCallerBalance() {
        givenPaidCall(200_000_000L);
        given(frame.getRecipientAddress()).willReturn(Address.ZERO);
        given(frame.getContractAddress()).willReturn(Address.fromHexString("0x16e"));
        assertThat(FrameUtils.isDelegateCall(frame)).isTrue();

        final var result = subject.price(attempt, call, GetChannelTranslator.GET_CHANNEL_QUEUE_STATE, frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().gasRequirement()).isEqualTo(PRICED_GAS_REQUIREMENT);
        assertThat(result.nonGasCost()).isZero();
        verify(frame, never()).getValue();
        verifyNoInteractions(updater);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void pricingPreservesCallResultAndRecordBuilder(final boolean viewCall) {
        givenPaidCall(200_000_000L);
        final var recordBuilder = mock(ContractCallStreamBuilder.class);
        final var precompileResult = FullResult.successResult(Bytes.fromHexString("1234"), GAS_REQUIREMENT)
                .result();
        given(call.execute(frame))
                .willReturn(
                        gasOnly(new FullResult(precompileResult, GAS_REQUIREMENT, recordBuilder), SUCCESS, viewCall));

        final var result = subject.price(attempt, call, GetChannelTranslator.GET_CHANNEL_QUEUE_STATE, frame);

        assertThat(result.fullResult().result()).isSameAs(precompileResult);
        assertThat(result.fullResult().output()).isEqualTo(Bytes.fromHexString("1234"));
        assertThat(result.fullResult().recordBuilder()).isSameAs(recordBuilder);
        assertThat(result.fullResult().gasRequirement()).isEqualTo(PRICED_GAS_REQUIREMENT);
        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.isViewCall()).isEqualTo(viewCall);
        assertThat(result.nonGasCost()).isZero();
    }

    @Test
    void quotedGasStillExceedsInsufficientFrameAllowance() {
        givenPaidCall(200_000_000L);

        final var result = subject.price(attempt, call, GetChannelTranslator.GET_CHANNEL_QUEUE_STATE, frame);

        // The common execution path compares this quote with remaining gas and reports INSUFFICIENT_GAS.
        assertThat(result.fullResult().gasRequirement()).isEqualTo(PRICED_GAS_REQUIREMENT);
        assertThat(result.fullResult().gasRequirement()).isGreaterThan(frame.getRemainingGas());
        assertThat(result.nonGasCost()).isZero();
    }

    @Test
    void failedCallRetainsNativeGasAndResponse() {
        final var failed = gasOnly(
                FullResult.ordinalRevertResult(CLPR_CHANNEL_NOT_FOUND, GAS_REQUIREMENT), CLPR_CHANNEL_NOT_FOUND, true);
        given(call.execute(frame)).willReturn(failed);

        final var result = subject.price(attempt, call, GetChannelTranslator.GET_CHANNEL_QUEUE_STATE, frame);

        assertThat(result).isSameAs(failed);
        verifyNoInteractions(attempt, operations, updater);
    }

    @Test
    void localQueryRetainsNativeGas() {
        final var result = subject.price(attempt, call, GetChannelTranslator.GET_CHANNEL_QUEUE_STATE, frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().gasRequirement()).isEqualTo(GAS_REQUIREMENT);
        assertThat(result.nonGasCost()).isZero();
        verifyNoInteractions(attempt, operations, updater);
    }

    @Test
    void nativeVerifierDispatchRetainsNativeGas() {
        givenTransaction();
        given(operations.isClprVerifierDispatch()).willReturn(true);

        final var result = subject.price(attempt, call, VerifyConfigTranslator.VERIFY_CONFIG, frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().gasRequirement()).isEqualTo(GAS_REQUIREMENT);
        assertThat(result.nonGasCost()).isZero();
        verifyNoInteractions(updater);
    }

    @Test
    void nativeVerifierDispatchStillPricesRouterReads() {
        givenPaidCall(200_000_000L);
        given(operations.isClprVerifierDispatch()).willReturn(true);

        final var result = subject.price(attempt, call, GetChannelTranslator.GET_CHANNEL_QUEUE_STATE, frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().gasRequirement()).isEqualTo(PRICED_GAS_REQUIREMENT);
        assertThat(result.nonGasCost()).isZero();
        verifyNoInteractions(updater);
    }

    private void givenPaidCall(final long target) {
        givenTransaction();
        given(frame.getGasPrice()).willReturn(Wei.of(2));
        given(operations.valueInTinybars(target)).willReturn(2 * PRICED_GAS_REQUIREMENT);
    }

    private void givenTransaction() {
        given(frame.hasContextVariable(HAPI_RECORD_BUILDER_CONTEXT_VARIABLE)).willReturn(true);
        given(frame.getRemainingGas()).willReturn(GAS_REQUIREMENT);
        given(attempt.enhancement())
                .willReturn(new Enhancement(
                        operations, mock(HederaNativeOperations.class), mock(SystemContractOperations.class)));
    }

    private static class TestClprContract extends AbstractClprSystemContract {
        private TestClprContract() {
            super("CLPR", mock(CallFactory.class), mock(GasCalculator.class), mock(ContractMetrics.class));
        }

        @Override
        protected boolean isRouter() {
            return true;
        }

        @Override
        protected CallType callTypeOf(final MessageFrame frame) {
            return CallType.DIRECT_OR_PROXY_REDIRECT;
        }

        private Call.PricedResult price(
                final AbstractCallAttempt<?> attempt,
                final Call call,
                final SystemContractMethod method,
                final MessageFrame frame) {
            return executeCall(attempt, call, Bytes.wrap(method.selector()), frame);
        }
    }
}
