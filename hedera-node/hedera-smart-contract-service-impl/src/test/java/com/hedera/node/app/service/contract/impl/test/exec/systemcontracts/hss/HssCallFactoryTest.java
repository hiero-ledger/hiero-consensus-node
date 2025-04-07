// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract.HSS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_SCHEDULE_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.bytesForRedirectScheduleTxn;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static org.hyperledger.besu.datatypes.Address.ALTBN128_ADD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAddressChecks;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.DispatchForResponseCodeHssCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallFactory;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.signschedule.SignScheduleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.SyntheticIds;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HssCallFactoryTest extends CallTestBase {
    @Mock
    private CallAddressChecks addressChecks;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private SignatureVerifier signatureVerifier;

    @Mock
    private AddressIdConverter idConverter;

    @Mock
    private SyntheticIds syntheticIds;

    @Mock
    private MessageFrame frame;

    @Mock
    private MessageFrame initialFrame;

    private final Deque<MessageFrame> stack = new ArrayDeque<>();

    @Mock
    private ProxyWorldUpdater updater;

    @Mock
    private Schedule schedule;

    @Mock
    private Key maybeEthSenderKey;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private HssCallFactory subject;

    @BeforeEach
    void setUp() {
        subject = new HssCallFactory(
                syntheticIds,
                addressChecks,
                verificationStrategies,
                signatureVerifier,
                List.of(new SignScheduleTranslator(systemContractMethodRegistry, contractMetrics)),
                systemContractMethodRegistry);
    }

    @Test
    void instantiatesCallWithInContextEnhancementAndDelegateCallInfo() {
        given(initialFrame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE))
                .willReturn(DEFAULT_CONFIG);
        given(initialFrame.getContextVariable(FrameUtils.SYSTEM_CONTRACT_GAS_CALCULATOR_CONTEXT_VARIABLE))
                .willReturn(systemContractGasCalculator);
        stack.push(initialFrame);
        stack.addFirst(frame);
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getWorldUpdater()).willReturn(updater);
        given(updater.enhancement()).willReturn(mockEnhancement());
        given(frame.getSenderAddress()).willReturn(EIP_1014_ADDRESS);
        given(addressChecks.hasParentDelegateCall(frame)).willReturn(true);
        given(syntheticIds.converterFor(nativeOperations)).willReturn(idConverter);
        given(nativeOperations.getSchedule(CALLED_SCHEDULE_ID)).willReturn(schedule);
        given(nativeOperations.getAccount(A_NEW_ACCOUNT_ID)).willReturn(SOMEBODY);
        given(schedule.scheduleId()).willReturn(CALLED_SCHEDULE_ID);
        given(idConverter.convertSender(EIP_1014_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(EIP_1014_ADDRESS, true, nativeOperations))
                .willReturn(verificationStrategy);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var input = bytesForRedirectScheduleTxn(
                SignScheduleTranslator.SIGN_SCHEDULE_PROXY.selector(),
                asLongZeroAddress(entityIdFactory, CALLED_SCHEDULE_ID.scheduleNum()));
        final var attempt = subject.createCallAttemptFrom(
                HSS_CONTRACT_ID, input, FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT, frame);
        final var call = Objects.requireNonNull(attempt.asExecutableCall());

        assertInstanceOf(DispatchForResponseCodeHssCall.class, call);
    }

    @Test
    void instantiatesDirectCall() {
        given(initialFrame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE))
                .willReturn(DEFAULT_CONFIG);
        given(initialFrame.getContextVariable(FrameUtils.SYSTEM_CONTRACT_GAS_CALCULATOR_CONTEXT_VARIABLE))
                .willReturn(systemContractGasCalculator);
        stack.push(initialFrame);
        stack.addFirst(frame);
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getWorldUpdater()).willReturn(updater);
        given(updater.enhancement()).willReturn(mockEnhancement());
        given(frame.getSenderAddress()).willReturn(ALTBN128_ADD);
        given(idConverter.convertSender(ALTBN128_ADD)).willReturn(A_NEW_ACCOUNT_ID);
        given(addressChecks.hasParentDelegateCall(frame)).willReturn(true);
        given(syntheticIds.converterFor(nativeOperations)).willReturn(idConverter);
        given(nativeOperations.getSchedule(CALLED_SCHEDULE_ID)).willReturn(schedule);
        given(nativeOperations.getAccount(A_NEW_ACCOUNT_ID)).willReturn(SOMEBODY);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(schedule.scheduleId()).willReturn(CALLED_SCHEDULE_ID);
        given(idConverter.convertSender(ALTBN128_ADD)).willReturn(A_NEW_ACCOUNT_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(ALTBN128_ADD, true, nativeOperations))
                .willReturn(verificationStrategy);

        final var input = bytesForRedirectScheduleTxn(
                SignScheduleTranslator.SIGN_SCHEDULE_PROXY.selector(),
                asLongZeroAddress(entityIdFactory, CALLED_SCHEDULE_ID.scheduleNum()));
        final var attempt = subject.createCallAttemptFrom(
                HSS_CONTRACT_ID, input, FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT, frame);
        final var call = Objects.requireNonNull(attempt.asExecutableCall());

        assertInstanceOf(DispatchForResponseCodeHssCall.class, call);
        assertEquals(A_NEW_ACCOUNT_ID, attempt.senderId());
    }
}
