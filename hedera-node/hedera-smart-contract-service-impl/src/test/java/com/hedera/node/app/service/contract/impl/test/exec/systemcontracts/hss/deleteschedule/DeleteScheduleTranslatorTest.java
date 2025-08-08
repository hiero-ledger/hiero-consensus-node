// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.deleteschedule;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.DispatchForResponseCodeHssCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.deleteschedule.DeleteScheduleTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.schedulecall.ScheduleCallTranslatorTest.TestSelector;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

class DeleteScheduleTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HssCallAttempt attempt;

    @Mock
    private AccountID payerId;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private Configuration configuration;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private Schedule schedule;

    @Mock
    private ScheduleID scheduleID;

    private DeleteScheduleTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new DeleteScheduleTranslator(systemContractMethodRegistry, contractMetrics);
    }

    private static List<TestSelector> deleteScheduleSelectors() {
        return List.of(
                new TestSelector(Bytes.wrap(DeleteScheduleTranslator.DELETE_SCHEDULE.selector()), true, true),
                new TestSelector(Bytes.wrap(DeleteScheduleTranslator.DELETE_SCHEDULE_PROXY.selector()), true, true),
                new TestSelector(Bytes.wrap("wrongSelector".getBytes()), true, false),
                new TestSelector(Bytes.wrap(DeleteScheduleTranslator.DELETE_SCHEDULE.selector()), false, false),
                new TestSelector(Bytes.wrap(DeleteScheduleTranslator.DELETE_SCHEDULE_PROXY.selector()), false, false));
    }

    @ParameterizedTest
    @MethodSource("deleteScheduleSelectors")
    public void testConfig(final TestSelector data) {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractScheduleCallEnabled()).willReturn(data.enabled());
        // when:
        attempt = createHssCallAttempt(data.selector(), false, configuration, List.of(subject));
        // then:
        assertEquals(data.present(), subject.identifyMethod(attempt).isPresent());
    }

    @Test
    void testTransactionBodyFor() {
        // when:
        final var body = subject.transactionBodyFor(scheduleID);
        // then:
        assertTrue(body.hasScheduleDelete());
        assertThat(body.scheduleDeleteOrThrow().scheduleID()).isEqualTo(scheduleID);
    }

    private static List<Bytes> deleteScheduleFunctions() {
        return List.of(
                Bytes.wrapByteBuffer(DeleteScheduleTranslator.DELETE_SCHEDULE.encodeCall(
                        Tuple.singleton(asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS)))),
                bytesForRedirectScheduleTxn(
                        DeleteScheduleTranslator.DELETE_SCHEDULE_PROXY.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS));
    }

    @Test
    void testScheduleIdFor() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHssCallAttempt(
                Bytes.wrapByteBuffer(DeleteScheduleTranslator.DELETE_SCHEDULE.encodeCall(
                        Tuple.singleton(asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS)))),
                false,
                configuration,
                List.of(subject));
        final var scheduleId = subject.scheduleIdFor(attempt);
        // then:
        assertEquals(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS), scheduleId.scheduleNum());
    }

    @Test
    void testScheduleIdForProxy() {
        given(nativeOperations.getSchedule(any(ScheduleID.class))).willReturn(schedule);
        given(schedule.scheduleId()).willReturn(scheduleID);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHssCallAttempt(
                bytesForRedirectScheduleTxn(
                        DeleteScheduleTranslator.DELETE_SCHEDULE_PROXY.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                false,
                configuration,
                List.of(subject));
        final var scheduleId = subject.scheduleIdFor(attempt);
        // then:
        assertEquals(scheduleID, scheduleId);
    }

    @Test
    void testScheduleIdForNonLongZeroAddress() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when:
        attempt = createHssCallAttempt(
                Bytes.wrapByteBuffer(DeleteScheduleTranslator.DELETE_SCHEDULE.encodeCall(
                        Tuple.singleton(asHeadlongAddress(Address.fromHexString(NON_LONG_ZERO_ADDRESS))))),
                false,
                configuration,
                List.of(subject));
        // then
        assertThrows(IllegalArgumentException.class, () -> subject.scheduleIdFor(attempt));
    }

    @ParameterizedTest
    @MethodSource("deleteScheduleFunctions")
    void testAttempt(Bytes input) {
        given(nativeOperations.getAccount(payerId)).willReturn(B_CONTRACT);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(payerId);
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, false, nativeOperations))
                .willReturn(verificationStrategy);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(HederaTestConfigBuilder.createConfig());
        // when:
        attempt = createHssCallAttempt(input, false, configuration, List.of(subject));
        final var call = subject.callFrom(attempt);
        // then:
        assertThat(call).isInstanceOf(DispatchForResponseCodeHssCall.class);
    }

    @Test
    void testAttemptWrongSelector() {
        // when:
        attempt = createHssCallAttempt(Bytes.wrap("wrongSelector".getBytes()), false, configuration, List.of(subject));
        // then:
        assertThrows(IllegalStateException.class, () -> subject.callFrom(attempt));
    }

    @Test
    void testGasRequirement() {
        // given:
        long expectedGas = 1000_000L;
        when(gasCalculator.gasRequirement(transactionBody, DispatchType.SCHEDULE_DELETE, payerId))
                .thenReturn(expectedGas);
        // when:
        long gas = DeleteScheduleTranslator.gasRequirement(transactionBody, gasCalculator, mockEnhancement(), payerId);
        // then:
        assertEquals(expectedGas, gas);
    }
}
