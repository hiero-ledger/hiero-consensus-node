// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.rejecttokens;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
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
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.rejecttokens.RejectTokensDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.rejecttokens.RejectTokensTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class RejectTokensTranslatorTest extends CallAttemptTestBase {

    @Mock
    private RejectTokensDecoder decoder;

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

    private RejectTokensTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new RejectTokensTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesHTSWithInvalidSig() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(true);
        // when:
        attempt = createHtsCallAttempt(BurnTranslator.BURN_TOKEN_V1, configuration, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesHTSWithConfigEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(true);
        // when:
        attempt = createHtsCallAttempt(RejectTokensTranslator.TOKEN_REJECT, configuration, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesHTSWithConfigDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(false);
        // when:
        attempt = createHtsCallAttempt(RejectTokensTranslator.TOKEN_REJECT, configuration, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesFungibleHRCWithConfigEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(true);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHtsCallAttemptForRedirect(RejectTokensTranslator.HRC_TOKEN_REJECT_FT, configuration, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFungibleHRCWithConfigDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(false);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHtsCallAttemptForRedirect(RejectTokensTranslator.HRC_TOKEN_REJECT_FT, configuration, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesNftHRCWithConfigEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(true);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHtsCallAttemptForRedirect(RejectTokensTranslator.HRC_TOKEN_REJECT_NFT, configuration, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesNftHRCWithConfigDisabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractRejectTokensEnabled()).willReturn(false);
        // when:
        attempt = createHtsCallAttempt(RejectTokensTranslator.HRC_TOKEN_REJECT_NFT, configuration, subject);
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
        // when:
        long result = RejectTokensTranslator.gasRequirement(transactionBody, gasCalculator, mockEnhancement(), payerId);

        assertEquals(expectedGas, result);
    }

    @Test
    void gasRequirementHRCFungible() {
        long expectedGas = 1000L;
        given(gasCalculator.gasRequirement(transactionBody, DispatchType.TOKEN_REJECT_FT, payerId))
                .willReturn(expectedGas);
        long result = RejectTokensTranslator.gasRequirementHRCFungible(
                transactionBody, gasCalculator, mockEnhancement(), payerId);

        assertEquals(expectedGas, result);
    }

    @Test
    void gasRequirementHRCNft() {
        long expectedGas = 1000L;
        given(gasCalculator.gasRequirement(transactionBody, DispatchType.TOKEN_REJECT_NFT, payerId))
                .willReturn(expectedGas);
        long result =
                RejectTokensTranslator.gasRequirementHRCNft(transactionBody, gasCalculator, mockEnhancement(), payerId);

        assertEquals(expectedGas, result);
    }

    @Test
    void callFromHtsTokenReject() {
        // given:
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .willReturn(verificationStrategy);
        // when:
        attempt = createHtsCallAttempt(RejectTokensTranslator.TOKEN_REJECT, configuration, subject);
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
        // when:
        attempt = createHtsCallAttempt(RejectTokensTranslator.HRC_TOKEN_REJECT_FT, configuration, subject);
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
        // when:
        attempt = createHtsCallAttempt(RejectTokensTranslator.HRC_TOKEN_REJECT_NFT, configuration, subject);
        var call = subject.callFrom(attempt);
        // then:
        assertEquals(DispatchForResponseCodeHtsCall.class, call.getClass());
        verify(decoder).decodeHrcTokenRejectNFT(attempt);
    }
}
