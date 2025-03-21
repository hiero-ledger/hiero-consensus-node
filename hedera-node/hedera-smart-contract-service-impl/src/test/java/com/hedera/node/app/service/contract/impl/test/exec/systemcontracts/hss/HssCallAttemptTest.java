// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.signschedule.SignScheduleTranslator;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HssCallAttemptTest extends CallAttemptTestBase {

    private List<CallTranslator<HssCallAttempt>> callTranslators;

    @Mock
    private ContractMetrics contractMetrics;

    @BeforeEach
    void setUp() {
        callTranslators = List.of(new SignScheduleTranslator(systemContractMethodRegistry, contractMetrics));
    }

    @Test
    void returnNullScheduleIfScheduleNotFound() {
        given(nativeOperations.getSchedule(
                        entityIdFactory.newScheduleId(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS))))
                .willReturn(null);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirectScheduleTxn(new byte[4], NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject =
                createHssCallAttempt(input, EIP_1014_ADDRESS, false, TestHelpers.DEFAULT_CONFIG, callTranslators);
        assertNull(subject.redirectScheduleTxn());
    }

    @Test
    void invalidSelectorLeadsToMissingCall() {
        final var input = TestHelpers.bytesForRedirectAccount(new byte[4], NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject =
                createHssCallAttempt(input, EIP_1014_ADDRESS, false, TestHelpers.DEFAULT_CONFIG, callTranslators);
        assertNull(subject.asExecutableCall());
    }

    @Test
    void isOnlyDelegatableContractKeysActiveTest() {
        final var input = TestHelpers.bytesForRedirectAccount(new byte[4], NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject =
                createHssCallAttempt(input, EIP_1014_ADDRESS, true, TestHelpers.DEFAULT_CONFIG, callTranslators);
        assertTrue(subject.isOnlyDelegatableContractKeysActive());
    }
}
