// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.getscheduledinfo;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledInfoTranslator.GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledInfoTranslator.GET_SCHEDULED_CREATE_NON_FUNGIBLE_TOKEN_INFO;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.signschedule.SignScheduleTranslator.SIGN_SCHEDULE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_SCHEDULE_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledFungibleTokenCreateCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledInfoTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledNonFungibleTokenCreateCall;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GetScheduledInfoTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HssCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private GetScheduledInfoTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new GetScheduledInfoTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void identifyMethodGetScheduledFungibleTokenTxn() {
        attempt = createHssCallAttempt(GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO, subject);
        final var result = subject.identifyMethod(attempt).isPresent();
        assertTrue(result);
    }

    @Test
    void identifyMethodGetScheduledNonFungibleTokenTxn() {
        attempt = createHssCallAttempt(GET_SCHEDULED_CREATE_NON_FUNGIBLE_TOKEN_INFO, subject);
        final var result = subject.identifyMethod(attempt).isPresent();
        assertTrue(result);
    }

    @Test
    void identifyMethodFailsForOtherSelector() {
        attempt = createHssCallAttempt(SIGN_SCHEDULE, subject);
        final var result = subject.identifyMethod(attempt).isPresent();
        assertFalse(result);
    }

    @Test
    void createsFungibleTokenCall() {
        given(attempt.isSelector(GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO)).willReturn(true);
        given(attempt.inputBytes())
                .willReturn(GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO
                        .encodeCallWithArgs(ConversionUtils.headlongAddressOf(CALLED_SCHEDULE_ID))
                        .array());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(attempt.configuration()).willReturn(DEFAULT_CONFIG);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        var result = subject.callFrom(attempt);

        assertInstanceOf(GetScheduledFungibleTokenCreateCall.class, result);
    }

    @Test
    void createsNonFungibleTokenCall() {
        given(attempt.isSelector(GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO)).willReturn(false);
        given(attempt.inputBytes())
                .willReturn(GET_SCHEDULED_CREATE_NON_FUNGIBLE_TOKEN_INFO
                        .encodeCallWithArgs(ConversionUtils.headlongAddressOf(CALLED_SCHEDULE_ID))
                        .array());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(attempt.configuration()).willReturn(DEFAULT_CONFIG);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        var result = subject.callFrom(attempt);

        assertInstanceOf(GetScheduledNonFungibleTokenCreateCall.class, result);
    }
}
