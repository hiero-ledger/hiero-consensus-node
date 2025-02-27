// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.cancelairdrops;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelectorAndCustomConfig;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelectorForRedirectWithConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.cancelairdrops.TokenCancelAirdropDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.cancelairdrops.TokenCancelAirdropTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenCancelAirdropTranslatorTest {

    @Mock
    private TokenCancelAirdropDecoder decoder;

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private Configuration configuration;

    @Mock
    private Enhancement enhancement;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private HederaNativeOperations nativeOperations;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private AccountID payerId;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private TokenCancelAirdropTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new TokenCancelAirdropTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesHTSCancelAirdropEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(true);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                TokenCancelAirdropTranslator.CANCEL_AIRDROPS,
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
    void matchesFailsOnWrongSelector() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(true);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                MintTranslator.MINT,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesHTSCancelAirdropDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(false);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                TokenCancelAirdropTranslator.CANCEL_AIRDROPS,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesHRCCancelFTAirdropEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(true);
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        attempt = prepareHtsAttemptWithSelectorForRedirectWithConfig(
                TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_FT,
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
    void matchesHRCCancelAirdropDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(false);
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        attempt = prepareHtsAttemptWithSelectorForRedirectWithConfig(
                TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_FT,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesHRCCancelNFTAirdropEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(true);
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        attempt = prepareHtsAttemptWithSelectorForRedirectWithConfig(
                TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_NFT,
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
    void matchesHRCCancelNFTAirdropDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(false);
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        attempt = prepareHtsAttemptWithSelectorForRedirectWithConfig(
                TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_NFT,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void gasRequirementCalculatesCorrectly() {
        long expectedGas = 1000L;
        given(gasCalculator.gasRequirement(transactionBody, DispatchType.TOKEN_CANCEL_AIRDROP, payerId))
                .willReturn(expectedGas);

        long result = TokenCancelAirdropTranslator.gasRequirement(transactionBody, gasCalculator, enhancement, payerId);

        assertEquals(expectedGas, result);
    }

    @Test
    void callFromHtsCancelAirdrop() {
        // given:
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .willReturn(verificationStrategy);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                TokenCancelAirdropTranslator.CANCEL_AIRDROPS,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        // when:
        var call = subject.callFrom(attempt);

        // then:
        assertEquals(DispatchForResponseCodeHtsCall.class, call.getClass());
        verify(decoder).decodeCancelAirdrop(attempt);
    }

    @Test
    void callFromHRCCancelFTAirdrop() {
        // given:
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .willReturn(verificationStrategy);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_FT,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        // when:
        var call = subject.callFrom(attempt);

        // then:
        assertEquals(DispatchForResponseCodeHtsCall.class, call.getClass());
        verify(decoder).decodeCancelAirdropFT(attempt);
    }

    @Test
    void callFromHRCCancelNFTAirdrop() {
        // given:
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .willReturn(verificationStrategy);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_NFT,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        // when:
        var call = subject.callFrom(attempt);

        // then:
        assertEquals(DispatchForResponseCodeHtsCall.class, call.getClass());
        verify(decoder).decodeCancelAirdropNFT(attempt);
    }
}
