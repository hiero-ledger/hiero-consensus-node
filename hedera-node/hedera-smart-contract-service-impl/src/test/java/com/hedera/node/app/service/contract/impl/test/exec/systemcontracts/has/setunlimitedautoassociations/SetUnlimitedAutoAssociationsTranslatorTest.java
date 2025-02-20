// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.setunlimitedautoassociations;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.setunlimitedautoassociations.SetUnlimitedAutoAssociationsTranslator.SET_UNLIMITED_AUTO_ASSOC;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHasAttemptWithSelectorAndCustomConfig;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.setunlimitedautoassociations.SetUnlimitedAutoAssociationsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.setunlimitedautoassociations.SetUnlimitedAutoAssociationsTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SetUnlimitedAutoAssociationsTranslatorTest {

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
    private Configuration configuration;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private SetUnlimitedAutoAssociationsTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new SetUnlimitedAutoAssociationsTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesWhenEnabled() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractSetUnlimitedAutoAssociationsEnabled())
                .willReturn(true);
        attempt = prepareHasAttemptWithSelectorAndCustomConfig(
                SET_UNLIMITED_AUTO_ASSOC,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesWhenDisabled() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractSetUnlimitedAutoAssociationsEnabled())
                .willReturn(false);
        attempt = prepareHasAttemptWithSelectorAndCustomConfig(
                SET_UNLIMITED_AUTO_ASSOC,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromWithTrueValue() {
        final var inputBytes = SET_UNLIMITED_AUTO_ASSOC.encodeCallWithArgs(true);
        given(attempt.inputBytes()).willReturn(inputBytes.array());
        given(attempt.enhancement()).willReturn(enhancement);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(SetUnlimitedAutoAssociationsCall.class);
    }

    @Test
    void callFromWithFalseValue() {
        final var inputBytes = SET_UNLIMITED_AUTO_ASSOC.encodeCallWithArgs(false);
        given(attempt.inputBytes()).willReturn(inputBytes.array());
        given(attempt.enhancement()).willReturn(enhancement);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(SetUnlimitedAutoAssociationsCall.class);
    }
}
