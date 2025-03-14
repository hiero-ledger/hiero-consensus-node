// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract.HSS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.signschedule.SignScheduleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HssCallAttemptTest extends CallTestBase {
    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private SignatureVerifier signatureVerifier;

    private List<CallTranslator<HssCallAttempt>> callTranslators;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    @BeforeEach
    void setUp() {
        callTranslators = List.of(new SignScheduleTranslator(systemContractMethodRegistry, contractMetrics));
    }

    @Test
    void returnNullScheduleIfScheduleNotFound() {
        given(nativeOperations.getSchedule(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(null);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirectScheduleTxn(new byte[4], NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = new HssCallAttempt(
                HSS_CONTRACT_ID,
                input,
                EIP_1014_ADDRESS,
                false,
                mockEnhancement(),
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                callTranslators,
                systemContractMethodRegistry,
                false);
        assertNull(subject.redirectScheduleTxn());
    }

    @Test
    void invalidSelectorLeadsToMissingCall() {
        final var input = TestHelpers.bytesForRedirectAccount(new byte[4], NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = new HssCallAttempt(
                HSS_CONTRACT_ID,
                input,
                EIP_1014_ADDRESS,
                false,
                mockEnhancement(),
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                callTranslators,
                systemContractMethodRegistry,
                false);
        assertNull(subject.asExecutableCall());
    }

    @Test
    void isOnlyDelegatableContractKeysActiveTest() {
        final var input = TestHelpers.bytesForRedirectAccount(new byte[4], NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = new HssCallAttempt(
                HSS_CONTRACT_ID,
                input,
                EIP_1014_ADDRESS,
                true,
                mockEnhancement(),
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                callTranslators,
                systemContractMethodRegistry,
                false);
        assertTrue(subject.isOnlyDelegatableContractKeysActive());
    }
}
