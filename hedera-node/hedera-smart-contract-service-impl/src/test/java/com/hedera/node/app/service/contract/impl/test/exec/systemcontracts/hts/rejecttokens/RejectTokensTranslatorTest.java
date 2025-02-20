// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.rejecttokens;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelectorAndCustomConfig;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelectorForRedirectWithConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.token.TokenReference;
import com.hedera.hapi.node.token.TokenRejectTransactionBody;
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
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.rejecttokens.RejectTokensDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.rejecttokens.RejectTokensTranslator;
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
public class RejectTokensTranslatorTest {

    @Mock
    private RejectTokensDecoder decoder;

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

    private RejectTokensTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new RejectTokensTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesHTSWithInvalidSig() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(true);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                BurnTranslator.BURN_TOKEN_V1, // obvs wrong selector
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
    void matchesHTSWithConfigEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(true);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                RejectTokensTranslator.TOKEN_REJECT,
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
    void matchesHTSWithConfigDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(false);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                RejectTokensTranslator.TOKEN_REJECT,
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
    void matchesFungibleHRCWithConfigEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(true);
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        attempt = prepareHtsAttemptWithSelectorForRedirectWithConfig(
                RejectTokensTranslator.HRC_TOKEN_REJECT_FT,
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
    void matchesFungibleHRCWithConfigDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(false);
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        attempt = prepareHtsAttemptWithSelectorForRedirectWithConfig(
                RejectTokensTranslator.HRC_TOKEN_REJECT_FT,
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
    void matchesNftHRCWithConfigEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(true);
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        attempt = prepareHtsAttemptWithSelectorForRedirectWithConfig(
                RejectTokensTranslator.HRC_TOKEN_REJECT_NFT,
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
    void matchesNftHRCWithConfigDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(false);
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        attempt = prepareHtsAttemptWithSelectorForRedirectWithConfig(
                RejectTokensTranslator.HRC_TOKEN_REJECT_NFT,
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
        final var body = TokenRejectTransactionBody.newBuilder()
                .rejections(TokenReference.newBuilder()
                        .fungibleToken(FUNGIBLE_TOKEN_ID)
                        .build())
                .owner(SENDER_ID)
                .build();
        given(gasCalculator.canonicalPriceInTinycents(DispatchType.TOKEN_REJECT_FT))
                .willReturn(expectedGas);
        given(transactionBody.tokenReject()).willReturn(body);
        given(gasCalculator.gasRequirementWithTinycents(transactionBody, payerId, expectedGas))
                .willReturn(expectedGas);
        long result = RejectTokensTranslator.gasRequirement(transactionBody, gasCalculator, enhancement, payerId);

        assertEquals(expectedGas, result);
    }

    @Test
    void gasRequirementHRCFungible() {
        long expectedGas = 1000L;
        given(gasCalculator.gasRequirement(transactionBody, DispatchType.TOKEN_REJECT_FT, payerId))
                .willReturn(expectedGas);
        long result =
                RejectTokensTranslator.gasRequirementHRCFungible(transactionBody, gasCalculator, enhancement, payerId);

        assertEquals(expectedGas, result);
    }

    @Test
    void gasRequirementHRCNft() {
        long expectedGas = 1000L;
        given(gasCalculator.gasRequirement(transactionBody, DispatchType.TOKEN_REJECT_NFT, payerId))
                .willReturn(expectedGas);
        long result = RejectTokensTranslator.gasRequirementHRCNft(transactionBody, gasCalculator, enhancement, payerId);

        assertEquals(expectedGas, result);
    }

    @Test
    void callFromHtsTokenReject() {
        // given:
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .willReturn(verificationStrategy);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                RejectTokensTranslator.TOKEN_REJECT,
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
        verify(decoder).decodeTokenRejects(attempt);
    }

    @Test
    void callFromHRCCancelFTAirdrop() {
        // given:
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .willReturn(verificationStrategy);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                RejectTokensTranslator.HRC_TOKEN_REJECT_FT,
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
        verify(decoder).decodeHrcTokenRejectFT(attempt);
    }

    @Test
    void callFromHRCCancelNFTAirdrop() {
        // given:
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .willReturn(verificationStrategy);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                RejectTokensTranslator.HRC_TOKEN_REJECT_NFT,
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
        verify(decoder).decodeHrcTokenRejectNFT(attempt);
    }
}
