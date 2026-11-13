// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.HAPI_RECORD_BUILDER_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
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
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CallType;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AbstractClprSystemContractTest {
    private static final long GAS_REQUIREMENT = 5_000L;
    private static final long TOP_UP = 10_000L;

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
        given(frame.getMessageFrameStack()).willReturn(new ArrayDeque<>());
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
    void chargesSuccessfulCallAtItsTargetLessGas(final SystemContractMethod method, final long target) {
        givenPaidCall(target);
        given(frame.getValue()).willReturn(Wei.of(TOP_UP + 1));
        given(frame.getRecipientAddress()).willReturn(Address.ZERO);
        given(frame.getContractAddress()).willReturn(Address.ZERO);
        given(frame.getWorldUpdater()).willReturn(updater);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(updater.collectClprFee(SENDER_ID, TOP_UP)).willReturn(true);

        final var result = subject.price(attempt, call, method, frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.nonGasCost()).isEqualTo(TOP_UP);
    }

    @Test
    void onChainStaticReadCannotPayTopUp() {
        givenPaidCall(200_000_000L);
        given(frame.isStatic()).willReturn(true);

        final var result = subject.price(attempt, call, GetChannelTranslator.GET_CHANNEL_QUEUE_STATE, frame);

        assertThat(result.responseCode()).isEqualTo(INSUFFICIENT_TX_FEE);
        assertThat(result.nonGasCost()).isZero();
        verifyNoInteractions(updater);
    }

    @Test
    void localQueryDoesNotCollectValue() {
        final var result = subject.price(attempt, call, GetChannelTranslator.GET_CHANNEL_QUEUE_STATE, frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.nonGasCost()).isZero();
        verifyNoInteractions(attempt, operations, updater);
    }

    @Test
    void nativeVerifierDispatchDoesNotCollectValue() {
        givenTransaction();
        given(operations.isClprVerifierDispatch()).willReturn(true);

        final var result = subject.price(attempt, call, VerifyConfigTranslator.VERIFY_CONFIG, frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.nonGasCost()).isZero();
        verifyNoInteractions(updater);
    }

    private void givenPaidCall(final long target) {
        givenTransaction();
        given(frame.getGasPrice()).willReturn(Wei.of(2));
        given(operations.valueInTinybars(target)).willReturn(TOP_UP + 2 * GAS_REQUIREMENT);
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
