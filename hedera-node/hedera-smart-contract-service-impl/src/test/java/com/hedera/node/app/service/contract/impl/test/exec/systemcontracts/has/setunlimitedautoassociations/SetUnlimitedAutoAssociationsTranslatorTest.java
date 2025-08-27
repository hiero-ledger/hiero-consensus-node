// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.setunlimitedautoassociations;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.setunlimitedautoassociations.SetUnlimitedAutoAssociationsTranslator.SET_UNLIMITED_AUTO_ASSOC;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.setunlimitedautoassociations.SetUnlimitedAutoAssociationsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.setunlimitedautoassociations.SetUnlimitedAutoAssociationsTranslator;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class SetUnlimitedAutoAssociationsTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HasCallAttempt attempt;

    @Mock
    private Configuration configuration;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private ContractMetrics contractMetrics;

    private SetUnlimitedAutoAssociationsTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new SetUnlimitedAutoAssociationsTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesWhenEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractSetUnlimitedAutoAssociationsEnabled())
                .willReturn(true);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when
        attempt = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(SET_UNLIMITED_AUTO_ASSOC.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                configuration,
                subject);
        // then
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesWhenDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractSetUnlimitedAutoAssociationsEnabled())
                .willReturn(false);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when
        attempt = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(SET_UNLIMITED_AUTO_ASSOC.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                configuration,
                subject);
        // then
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromWithTrueValue() {
        final var inputBytes = SET_UNLIMITED_AUTO_ASSOC.encodeCallWithArgs(true);
        given(attempt.inputBytes()).willReturn(inputBytes.array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        // when
        final var call = subject.callFrom(attempt);
        // then
        assertThat(call).isInstanceOf(SetUnlimitedAutoAssociationsCall.class);
    }

    @Test
    void callFromWithFalseValue() {
        final var inputBytes = SET_UNLIMITED_AUTO_ASSOC.encodeCallWithArgs(false);
        given(attempt.inputBytes()).willReturn(inputBytes.array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        // when
        final var call = subject.callFrom(attempt);
        // then
        assertThat(call).isInstanceOf(SetUnlimitedAutoAssociationsCall.class);
    }
}
