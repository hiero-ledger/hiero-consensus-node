/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.updatetokencustomfees;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.updatetokencustomfees.UpdateTokenCustomFeesTranslator.UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.updatetokencustomfees.UpdateTokenCustomFeesTranslator.UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelectorAndCustomConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.updatetokencustomfees.UpdateTokenCustomFeesDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.updatetokencustomfees.UpdateTokenCustomFeesTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.config.api.Configuration;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateTokenCustomFeesTranslatorTest extends CallTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    Configuration configuration;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private TokensConfig tokensConfig;

    @Mock
    private HederaWorldUpdater.Enhancement enhancement;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private ProxyWorldUpdater proxyWorldUpdater;

    @Mock
    private SystemContractOperations systemOperations;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private final UpdateTokenCustomFeesDecoder decoder = new UpdateTokenCustomFeesDecoder();

    private UpdateTokenCustomFeesTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new UpdateTokenCustomFeesTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesIsTrueWhenSelectorForFungibleIsCorrect() {
        // given:
        setConfiguration(true);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION,
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
    void matchesIsTrueWhenSelectorForNFTIsCorrect() {
        // given:
        setConfiguration(true);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION,
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
    void matchesFailsIfFeatureFlagDisabled() {
        // given:
        setConfiguration(false);
        // expect:
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesIsFalseWhenSelectorsAreIncorrect() {
        // given:
        setConfiguration(true);
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                BURN_TOKEN_V2,
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
    void callFromFungibleTest() {
        Tuple tuple = Tuple.of(
                FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                new Tuple[] {
                    Tuple.of(
                            10L,
                            Address.wrap("0x0000000000000000000000000000000000000000"),
                            true,
                            false,
                            OWNER_HEADLONG_ADDRESS)
                },
                new Tuple[0]);
        byte[] inputBytes = Bytes.wrapByteBuffer(UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCall(tuple))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.selector()).willReturn(UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.selector());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(addressIdConverter.convert(any())).willReturn(OWNER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.configuration()).willReturn(configuration);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.maxCustomFeesAllowed()).willReturn(10);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(DispatchForResponseCodeHtsCall.class);
    }

    @Test
    void callFromNonFungibleTest() {
        Tuple tuple = Tuple.of(
                NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                new Tuple[] {
                    Tuple.of(
                            10L,
                            Address.wrap("0x0000000000000000000000000000000000000000"),
                            true,
                            false,
                            OWNER_HEADLONG_ADDRESS)
                },
                new Tuple[0]);
        byte[] inputBytes = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCall(
                                tuple))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.selector())
                .willReturn(UpdateTokenCustomFeesTranslator.UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.selector());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convertSender(any())).willReturn(SENDER_ID);
        given(addressIdConverter.convert(any())).willReturn(OWNER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.configuration()).willReturn(configuration);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.maxCustomFeesAllowed()).willReturn(10);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(DispatchForResponseCodeHtsCall.class);
    }

    @Test
    void shouldFailIfCustomFeesExceedsMaxAllowed() {
        final var customFees = new Tuple[] {
            Tuple.of(
                    10L,
                    Address.wrap("0x0000000000000000000000000000000000000000"),
                    true,
                    false,
                    OWNER_HEADLONG_ADDRESS)
        };

        Tuple tuple = Tuple.of(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, customFees, new Tuple[0]);

        byte[] inputBytes = Bytes.wrapByteBuffer(UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCall(tuple))
                .toArray();

        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.selector()).willReturn(UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.selector());
        given(attempt.configuration()).willReturn(configuration);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.maxCustomFeesAllowed()).willReturn(0);
        // Execute the call and verify it fails with CUSTOM_FEES_LIST_TOO_LONG
        assertThatExceptionOfType(HandleException.class)
                .isThrownBy(() -> subject.callFrom(attempt))
                .withMessage(CUSTOM_FEES_LIST_TOO_LONG.protoName());
    }

    private void setConfiguration(final boolean enabled) {
        lenient().when(attempt.configuration()).thenReturn(configuration);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractUpdateCustomFeesEnabled()).willReturn(enabled);
    }
}
