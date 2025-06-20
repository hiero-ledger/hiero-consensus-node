// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_EOA_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.bytesForRedirectAccount;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAddressChecks;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallFactory;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator;
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
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HasCallFactoryTest extends CallTestBase {
    @Mock
    private CallAddressChecks addressChecks;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private VerificationStrategies verificationStrategies;

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
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private HasCallFactory subject;

    @BeforeEach
    void setUp() {
        subject = new HasCallFactory(
                syntheticIds,
                addressChecks,
                verificationStrategies,
                signatureVerifier,
                List.of(new HbarAllowanceTranslator(systemContractMethodRegistry, contractMetrics)),
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
        given(idConverter.convert(asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS)))
                .willReturn(A_NEW_ACCOUNT_ID);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var input = bytesForRedirectAccount(
                HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY.encodeCallWithArgs(
                        asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS)),
                CALLED_EOA_ID);
        final var attempt = subject.createCallAttemptFrom(
                HAS_CONTRACT_ID, input, FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT, frame);
        final var call = Objects.requireNonNull(attempt.asExecutableCall());

        assertInstanceOf(HbarAllowanceCall.class, call);
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
        given(frame.getSenderAddress()).willReturn(Address.ALTBN128_ADD);
        given(idConverter.convertSender(Address.ALTBN128_ADD)).willReturn(A_NEW_ACCOUNT_ID);
        given(addressChecks.hasParentDelegateCall(frame)).willReturn(true);
        given(syntheticIds.converterFor(nativeOperations)).willReturn(idConverter);
        given(idConverter.convert(asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS)))
                .willReturn(A_NEW_ACCOUNT_ID);
        given(idConverter.convert(asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(B_NEW_ACCOUNT_ID);

        final var input = Bytes.wrap(HbarAllowanceTranslator.HBAR_ALLOWANCE
                .encodeCallWithArgs(
                        asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS),
                        asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS))
                .array());
        final var attempt = subject.createCallAttemptFrom(
                HAS_CONTRACT_ID, input, FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT, frame);
        final var call = Objects.requireNonNull(attempt.asExecutableCall());

        assertInstanceOf(HbarAllowanceCall.class, call);
        assertEquals(A_NEW_ACCOUNT_ID, attempt.senderId());
    }
}
