/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.getscheduledinfo;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_SCHEDULE_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHssAttemptWithSelectorAndCustomConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledFungibleTokenCreateCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledInfoTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledNonFungibleTokenCreateCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.signschedule.SignScheduleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetScheduledInfoTranslatorTest {

    @Mock
    private HssCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private Enhancement enhancement;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private SignatureVerifier signatureVerifier;

    @Mock
    private SystemContractMethodRegistry systemContractMethodRegistry;

    @Mock
    private ContractMetrics contractMetrics;

    private GetScheduledInfoTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new GetScheduledInfoTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void identifyMethodGetScheduledFungibleTokenTxn() {
        attempt = prepareHssAttemptWithSelectorAndCustomConfig(
                GetScheduledInfoTranslator.GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                DEFAULT_CONFIG);

        final var result = subject.identifyMethod(attempt).isPresent();
        assertTrue(result);
    }

    @Test
    void identifyMethodGetScheduledNonFungibleTokenTxn() {
        attempt = prepareHssAttemptWithSelectorAndCustomConfig(
                GetScheduledInfoTranslator.GET_SCHEDULED_CREATE_NON_FUNGIBLE_TOKEN_INFO,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                DEFAULT_CONFIG);

        final var result = subject.identifyMethod(attempt).isPresent();
        assertTrue(result);
    }

    @Test
    void identifyMethodFailsForOtherSelector() {
        attempt = prepareHssAttemptWithSelectorAndCustomConfig(
                SignScheduleTranslator.SIGN_SCHEDULE,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                DEFAULT_CONFIG);

        final var result = subject.identifyMethod(attempt).isPresent();
        assertFalse(result);
    }

    @Test
    void createsFungibleTokenCall() {
        given(attempt.isSelector(GetScheduledInfoTranslator.GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO))
                .willReturn(true);
        given(attempt.inputBytes())
                .willReturn(GetScheduledInfoTranslator.GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO
                        .encodeCallWithArgs(ConversionUtils.headlongAddressOf(CALLED_SCHEDULE_ID))
                        .array());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(enhancement);
        given(attempt.configuration()).willReturn(DEFAULT_CONFIG);

        var result = subject.callFrom(attempt);

        assertInstanceOf(GetScheduledFungibleTokenCreateCall.class, result);
    }

    @Test
    void createsNonFungibleTokenCall() {
        given(attempt.isSelector(GetScheduledInfoTranslator.GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO))
                .willReturn(false);
        given(attempt.inputBytes())
                .willReturn(GetScheduledInfoTranslator.GET_SCHEDULED_CREATE_NON_FUNGIBLE_TOKEN_INFO
                        .encodeCallWithArgs(ConversionUtils.headlongAddressOf(CALLED_SCHEDULE_ID))
                        .array());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(enhancement);
        given(attempt.configuration()).willReturn(DEFAULT_CONFIG);

        var result = subject.callFrom(attempt);

        assertInstanceOf(GetScheduledNonFungibleTokenCreateCall.class, result);
    }
}
