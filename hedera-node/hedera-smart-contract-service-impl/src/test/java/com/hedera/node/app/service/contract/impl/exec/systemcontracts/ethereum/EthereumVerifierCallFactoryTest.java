// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.EthereumVerifierSystemContract.ETHEREUM_VERIFIER_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.datatypes.Address.ALTBN128_ADD;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAddressChecks;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.SyntheticIds;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.util.ArrayDeque;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class EthereumVerifierCallFactoryTest extends CallTestBase {
    @Mock
    private CallAddressChecks addressChecks;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private AddressIdConverter idConverter;

    @Mock
    private SyntheticIds syntheticIds;

    @Mock
    private MessageFrame frame;

    @Mock
    private MessageFrame initialFrame;

    @Mock
    private ProxyWorldUpdater updater;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry registry = new SystemContractMethodRegistry();
    private EthereumVerifierCallFactory subject;

    @BeforeEach
    void setUp() {
        subject = new EthereumVerifierCallFactory(
                syntheticIds, addressChecks, verificationStrategies, List.of(), registry);
    }

    @Test
    void createsAttemptFromFrameContext() {
        final var stack = new ArrayDeque<MessageFrame>();
        stack.push(initialFrame);
        stack.addFirst(frame);
        final var enhancement = mockEnhancement();
        final var contractId = ContractID.newBuilder()
                .contractNum(numberOfLongZero(Address.fromHexString(ETHEREUM_VERIFIER_EVM_ADDRESS)))
                .build();
        final var input = Bytes.wrap(new byte[] {1, 2, 3, 4});

        given(initialFrame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE))
                .willReturn(DEFAULT_CONFIG);
        given(initialFrame.getContextVariable(FrameUtils.SYSTEM_CONTRACT_GAS_CALCULATOR_CONTEXT_VARIABLE))
                .willReturn(systemContractGasCalculator);
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getWorldUpdater()).willReturn(updater);
        given(updater.enhancement()).willReturn(enhancement);
        given(frame.getSenderAddress()).willReturn(ALTBN128_ADD);
        given(frame.getRecipientAddress()).willReturn(Address.fromHexString(ETHEREUM_VERIFIER_EVM_ADDRESS));
        given(frame.isStatic()).willReturn(true);
        given(addressChecks.hasParentDelegateCall(frame)).willReturn(true);
        given(syntheticIds.converterFor(nativeOperations)).willReturn(idConverter);
        given(idConverter.convertSender(ALTBN128_ADD)).willReturn(A_NEW_ACCOUNT_ID);

        final var attempt =
                subject.createCallAttemptFrom(contractId, input, FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT, frame);

        assertThat(attempt.input()).isEqualTo(input);
        assertThat(attempt.senderAddress()).isEqualTo(ALTBN128_ADD);
        assertThat(attempt.senderId()).isEqualTo(A_NEW_ACCOUNT_ID);
        assertThat(attempt.systemContractGasCalculator()).isSameAs(systemContractGasCalculator);
        assertThat(attempt.isStaticCall()).isTrue();
    }
}
