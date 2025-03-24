// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.claimairdrops;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.claimairdrops.TokenClaimAirdropDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.claimairdrops.TokenClaimAirdropTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class TokenClaimAirdropTranslatorTest extends CallAttemptTestBase {

    @Mock
    private TokenClaimAirdropDecoder decoder;

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private Configuration configuration;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private AccountID payerId;

    @Mock
    private ContractMetrics contractMetrics;

    private TokenClaimAirdropTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new TokenClaimAirdropTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void testMatchesWhenClaimAirdropEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractClaimAirdropsEnabled()).willReturn(true);
        // when:
        attempt = createHtsCallAttempt(TokenClaimAirdropTranslator.CLAIM_AIRDROPS, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void testMatchesFailsOnRandomSelector() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractClaimAirdropsEnabled()).willReturn(true);
        // when:
        attempt = createHtsCallAttempt(MintTranslator.MINT, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void testMatchesWhenClaimAirdropDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractClaimAirdropsEnabled()).willReturn(false);
        // when:
        attempt = createHtsCallAttempt(TokenClaimAirdropTranslator.CLAIM_AIRDROPS, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void testMatchesHRCClaimFT() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractClaimAirdropsEnabled()).willReturn(true);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHtsCallAttemptForRedirect(
                TokenClaimAirdropTranslator.HRC_CLAIM_AIRDROP_FT, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void testMatchesHRCClaimNFT() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractClaimAirdropsEnabled()).willReturn(true);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHtsCallAttemptForRedirect(
                TokenClaimAirdropTranslator.HRC_CLAIM_AIRDROP_NFT, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void testMatchesHRCClaimFTDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractClaimAirdropsEnabled()).willReturn(false);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHtsCallAttemptForRedirect(
                TokenClaimAirdropTranslator.HRC_CLAIM_AIRDROP_FT, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void testMatchesHRCClaimNFTDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractClaimAirdropsEnabled()).willReturn(false);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHtsCallAttemptForRedirect(
                TokenClaimAirdropTranslator.HRC_CLAIM_AIRDROP_NFT, configuration, subject);
        // then:
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void testCallFromForClassic() {
        // given:
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .willReturn(verificationStrategy);
        // when:
        attempt = createHtsCallAttempt(TokenClaimAirdropTranslator.CLAIM_AIRDROPS, configuration, subject);
        var call = subject.callFrom(attempt);
        // then:
        assertEquals(DispatchForResponseCodeHtsCall.class, call.getClass());
        verify(decoder).decodeTokenClaimAirdrop(attempt);
    }

    @Test
    void callFromHRCClaimFTAirdrop() {
        // given:
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .willReturn(verificationStrategy);
        // when:
        attempt = createHtsCallAttempt(TokenClaimAirdropTranslator.HRC_CLAIM_AIRDROP_FT, configuration, subject);
        var call = subject.callFrom(attempt);
        // then:
        assertEquals(DispatchForResponseCodeHtsCall.class, call.getClass());
        verify(decoder).decodeHrcClaimAirdropFt(attempt);
    }

    @Test
    void callFromHRCCancelNFTAirdrop() {
        // given:
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .willReturn(verificationStrategy);
        // when:
        attempt = createHtsCallAttempt(TokenClaimAirdropTranslator.HRC_CLAIM_AIRDROP_NFT, configuration, subject);
        var call = subject.callFrom(attempt);
        // then:
        assertEquals(DispatchForResponseCodeHtsCall.class, call.getClass());
        verify(decoder).decodeHrcClaimAirdropNft(attempt);
    }

    @Test
    void testGasRequirement() {
        long expectedGas = 1000L;
        // when:
        when(gasCalculator.gasRequirement(transactionBody, DispatchType.TOKEN_CLAIM_AIRDROP, payerId))
                .thenReturn(expectedGas);
        long gas =
                TokenClaimAirdropTranslator.gasRequirement(transactionBody, gasCalculator, mockEnhancement(), payerId);
        // then:
        assertEquals(expectedGas, gas);
    }
}
