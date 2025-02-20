// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.evmaddressalias;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.getevmaddressalias.EvmAddressAliasTranslator.EVM_ADDRESS_ALIAS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveTranslator.HBAR_APPROVE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHasAttemptWithSelector;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.getevmaddressalias.EvmAddressAliasCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.getevmaddressalias.EvmAddressAliasTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvmAddressAliasTranslatorTest {
    @Mock
    private HasCallAttempt attempt;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private HederaWorldUpdater.Enhancement enhancement;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private SignatureVerifier signatureVerifier;

    @Mock
    private HederaNativeOperations nativeOperations;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private EvmAddressAliasTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new EvmAddressAliasTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesEvmAddressAlias() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        attempt = prepareHasAttemptWithSelector(
                EVM_ADDRESS_ALIAS,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
        assertEquals("0xdea3d081" /*copied from HIP-632*/, "0x" + EVM_ADDRESS_ALIAS.selectorHex());
    }

    @Test
    void failsOnInvalidSelector() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        attempt = prepareHasAttemptWithSelector(
                HBAR_APPROVE,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromEvmAddressAliasTest() {
        final Bytes inputBytes =
                Bytes.wrapByteBuffer(EVM_ADDRESS_ALIAS.encodeCall(Tuple.singleton(APPROVED_HEADLONG_ADDRESS)));
        givenCommonForCall(inputBytes);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(EvmAddressAliasCall.class);
    }

    private void givenCommonForCall(Bytes inputBytes) {
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(enhancement);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
    }
}
