// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.schedulenative;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulenative.ScheduleNativeTranslator.SCHEDULED_NATIVE_CALL;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.signschedule.SignScheduleTranslator.SIGN_SCHEDULE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HTS_SYSTEM_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_V3_TUPLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.any;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulenative.ScheduleNativeCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulenative.ScheduleNativeTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallFactory;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x167.CreateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x167.CreateTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import java.util.List;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ScheduleNativeTranslatorTest extends CallAttemptTestBase {

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private AccountID payerId;

    @Mock
    private HssCallAttempt attempt;

    @Mock
    private HtsCallFactory htsCallFactory;

    // Mock to populate the selectors map
    @Mock
    private CreateDecoder decoder;

    @Mock
    private Configuration configuration;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private ContractMetrics contractMetrics;

    private ScheduleNativeTranslator subject;

    @BeforeEach
    void setUp() {
        new CreateTranslator(decoder, systemContractMethodRegistry, contractMetrics);
        subject = new ScheduleNativeTranslator(htsCallFactory, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void testMatchesScheduleCreate() {
        // given
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractScheduleNativeEnabled()).willReturn(true);
        final var attempt = createHssCallAttempt(
                Bytes.wrapByteBuffer(SCHEDULED_NATIVE_CALL.encodeCallWithArgs(
                        asHeadlongAddress(HTS_SYSTEM_CONTRACT_ADDRESS),
                        CreateTranslator.CREATE_FUNGIBLE_TOKEN_V3
                                .encodeCall(CREATE_FUNGIBLE_V3_TUPLE)
                                .array(),
                        asHeadlongAddress(SENDER_ID.accountNum()))),
                false,
                configuration,
                List.of(subject));
        // when/then
        assertTrue(subject.identifyMethod(attempt).isPresent());
    }

    @Test
    void testMatchesFailsForOtherSelector() {
        // given
        final var attempt =
                createHssCallAttempt(Bytes.wrap(SIGN_SCHEDULE.selector()), false, DEFAULT_CONFIG, List.of(subject));
        // when/then
        assertFalse(subject.identifyMethod(attempt).isPresent());
    }

    @Test
    void calculatesGasRequirementCorrectly() {
        // given
        final var expectedGas = 1234L;
        given(gasCalculator.gasRequirement(transactionBody, DispatchType.SCHEDULE_CREATE, payerId))
                .willReturn(expectedGas);
        // when
        final var actualGas =
                ScheduleNativeTranslator.gasRequirement(transactionBody, gasCalculator, mockEnhancement(), payerId);
        // then
        assertEquals(expectedGas, actualGas);
    }

    @Test
    void createsCall() {
        // given
        final var inputBytes = Bytes.wrapByteBuffer(SCHEDULED_NATIVE_CALL.encodeCallWithArgs(
                asHeadlongAddress(HTS_SYSTEM_CONTRACT_ADDRESS),
                CreateTranslator.CREATE_FUNGIBLE_TOKEN_V3
                        .encodeCall(CREATE_FUNGIBLE_V3_TUPLE)
                        .array(),
                asHeadlongAddress(SENDER_ID.accountNum())));

        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.inputBytes()).willReturn(inputBytes.toArray());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.keySetFor()).willReturn(Set.of());
        given(attempt.systemContractID()).willReturn(HTS_167_CONTRACT_ID);
        given(addressIdConverter.convert(any())).willReturn(SENDER_ID);
        // when
        final var call = subject.callFrom(attempt);
        // then
        assertNotNull(call);
        assertInstanceOf(ScheduleNativeCall.class, call);
    }
}
