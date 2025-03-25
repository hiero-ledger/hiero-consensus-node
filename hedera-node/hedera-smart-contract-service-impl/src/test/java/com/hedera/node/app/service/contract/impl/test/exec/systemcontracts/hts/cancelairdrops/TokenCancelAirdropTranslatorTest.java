// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.cancelairdrops;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.cancelairdrops.TokenCancelAirdropDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.cancelairdrops.TokenCancelAirdropTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class TokenCancelAirdropTranslatorTest extends CallAttemptTestBase {

    @Mock
    private TokenCancelAirdropDecoder decoder;

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private Configuration configuration;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private AccountID payerId;

    @Mock
    private ContractMetrics contractMetrics;

    private TokenCancelAirdropTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new TokenCancelAirdropTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesHTSCancelAirdropEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(true);
        // when:
        attempt = createHtsCallAttempt(TokenCancelAirdropTranslator.CANCEL_AIRDROPS, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsOnWrongSelector() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(true);
        // when:
        attempt = createHtsCallAttempt(MintTranslator.MINT, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesHTSCancelAirdropDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(false);
        // when:
        attempt = createHtsCallAttempt(TokenCancelAirdropTranslator.CANCEL_AIRDROPS, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesHRCCancelFTAirdropEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(true);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHtsCallAttemptForRedirect(
                TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_FT, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesHRCCancelAirdropDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(false);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHtsCallAttemptForRedirect(
                TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_FT, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesHRCCancelNFTAirdropEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(true);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHtsCallAttemptForRedirect(
                TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_NFT, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesHRCCancelNFTAirdropDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractCancelAirdropsEnabled()).willReturn(false);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHtsCallAttemptForRedirect(
                TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_NFT, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void gasRequirementCalculatesCorrectly() {
        long expectedGas = 1000L;
        // when:
        given(gasCalculator.gasRequirement(transactionBody, DispatchType.TOKEN_CANCEL_AIRDROP, payerId))
                .willReturn(expectedGas);

        long result =
                TokenCancelAirdropTranslator.gasRequirement(transactionBody, gasCalculator, mockEnhancement(), payerId);
        // then:
        assertEquals(expectedGas, result);
    }

    @Test
    void callFromHtsCancelAirdrop() {
        // given:
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .willReturn(verificationStrategy);
        // when:
        attempt = createHtsCallAttempt(TokenCancelAirdropTranslator.CANCEL_AIRDROPS, configuration, subject);
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
        // when:
        attempt = createHtsCallAttempt(TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_FT, configuration, subject);
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
        // when:
        attempt = createHtsCallAttempt(TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_NFT, configuration, subject);
        var call = subject.callFrom(attempt);
        // then:
        assertEquals(DispatchForResponseCodeHtsCall.class, call.getClass());
        verify(decoder).decodeCancelAirdropNFT(attempt);
    }
}
