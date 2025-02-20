// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.fungibletokeninfo;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.fungibletokeninfo.FungibleTokenInfoTranslator.FUNGIBLE_TOKEN_INFO;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.fungibletokeninfo.FungibleTokenInfoTranslator.FUNGIBLE_TOKEN_INFO_V2;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelector;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelectorAndCustomConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.fungibletokeninfo.FungibleTokenInfoCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.fungibletokeninfo.FungibleTokenInfoTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FungibleTokenInfoTranslatorTest {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private Enhancement enhancement;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    Configuration configuration;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private FungibleTokenInfoTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new FungibleTokenInfoTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesFungibleTokenInfoTranslatorTest() {
        attempt = prepareHtsAttemptWithSelector(
                FUNGIBLE_TOKEN_INFO,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFungibleTokenInfoTranslatorTestV2() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractTokenInfoV2Enabled()).willReturn(true);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                FUNGIBLE_TOKEN_INFO_V2,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsIfIncorrectSelectorTest() {
        attempt = prepareHtsAttemptWithSelector(
                BURN_TOKEN_V2,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromTest() {
        final Tuple tuple = Tuple.singleton(FUNGIBLE_TOKEN_HEADLONG_ADDRESS);
        final Bytes inputBytes = Bytes.wrapByteBuffer(FUNGIBLE_TOKEN_INFO.encodeCall(tuple));
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(enhancement);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.isSelector(FUNGIBLE_TOKEN_INFO)).willReturn(true);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(FungibleTokenInfoCall.class);
    }

    @Test
    void callFromTestV2() {
        final Tuple tuple = Tuple.singleton(FUNGIBLE_TOKEN_HEADLONG_ADDRESS);
        final Bytes inputBytes = Bytes.wrapByteBuffer(FUNGIBLE_TOKEN_INFO_V2.encodeCall(tuple));
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(enhancement);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.configuration()).willReturn(configuration);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(FungibleTokenInfoCall.class);
    }
}
