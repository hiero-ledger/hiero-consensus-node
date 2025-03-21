// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.airdrops;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.airdrops.TokenAirdropTranslator.TOKEN_AIRDROP;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.airdrops.TokenAirdropDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.airdrops.TokenAirdropTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class TokenAirdropTranslatorTest extends CallAttemptTestBase {

    @Mock
    private TokenAirdropDecoder decoder;

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private Configuration configuration;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private AccountID payerId;

    @Mock
    private ContractMetrics contractMetrics;

    private TokenAirdropTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new TokenAirdropTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesWhenAirdropEnabled() {
        attempt = createHtsCallAttempt(TOKEN_AIRDROP, getTestConfiguration(true), subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void doesNotMatchWhenAirdropDisabled() {
        attempt = createHtsCallAttempt(TOKEN_AIRDROP, getTestConfiguration(false), subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesFailsForRandomSelector() {
        when(configuration.getConfigData(ContractsConfig.class)).thenReturn(contractsConfig);
        when(contractsConfig.systemContractAirdropTokensEnabled()).thenReturn(true);
        attempt = createHtsCallAttempt(MintTranslator.MINT, configuration, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void gasRequirementCalculatesCorrectly() {
        long expectedGas = 1000L;
        when(gasCalculator.gasRequirement(transactionBody, DispatchType.TOKEN_AIRDROP, payerId))
                .thenReturn(expectedGas);

        long result = TokenAirdropTranslator.gasRequirement(transactionBody, gasCalculator, mockEnhancement(), payerId);

        assertEquals(expectedGas, result);
    }

    @Test
    void callFromCreatesCorrectCall() {
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertEquals(DispatchForResponseCodeHtsCall.class, call.getClass());
        verify(decoder).decodeAirdrop(attempt);
    }

    @NonNull
    Configuration getTestConfiguration(final boolean enabled) {
        return HederaTestConfigBuilder.create()
                .withValue("contracts.systemContract.airdropTokens.enabled", enabled)
                .getOrCreateConfig();
    }
}
