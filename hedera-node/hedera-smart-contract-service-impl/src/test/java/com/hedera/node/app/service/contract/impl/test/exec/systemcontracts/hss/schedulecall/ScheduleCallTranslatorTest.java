// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.schedulecall;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_BESU_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_CONTRACT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.DispatchForResponseCodeHssCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulecall.ScheduleCallDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulecall.ScheduleCallTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

public class ScheduleCallTranslatorTest extends CallAttemptTestBase {

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

    private ScheduleCallTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new ScheduleCallTranslator(new ScheduleCallDecoder(), systemContractMethodRegistry, contractMetrics);
    }

    public record TestSelector(Bytes selector, boolean enabled, boolean present) {}

    private static List<TestSelector> scheduleCallSelectors() {
        return List.of(
                new TestSelector(Bytes.wrap(ScheduleCallTranslator.SCHEDULE_CALL.selector()), true, true),
                new TestSelector(Bytes.wrap(ScheduleCallTranslator.SCHEDULE_CALL_WITH_PAYER.selector()), true, true),
                new TestSelector(
                        Bytes.wrap(ScheduleCallTranslator.EXECUTE_CALL_ON_PAYER_SIGNATURE.selector()), true, true),
                new TestSelector(Bytes.wrap("wrongSelector".getBytes()), true, false),
                new TestSelector(Bytes.wrap(ScheduleCallTranslator.SCHEDULE_CALL.selector()), false, false),
                new TestSelector(Bytes.wrap(ScheduleCallTranslator.SCHEDULE_CALL_WITH_PAYER.selector()), false, false),
                new TestSelector(
                        Bytes.wrap(ScheduleCallTranslator.EXECUTE_CALL_ON_PAYER_SIGNATURE.selector()), false, false));
    }

    @ParameterizedTest
    @MethodSource("scheduleCallSelectors")
    public void testConfig(final TestSelector data) {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractScheduleCallEnabled()).willReturn(data.enabled());
        // when:
        attempt = createHssCallAttempt(data.selector(), false, configuration, List.of(subject));
        // then:
        assertEquals(data.present(), subject.identifyMethod(attempt).isPresent());
    }

    public record TestFunction(Bytes input, Object senderParameter, Address senderAddress) {}

    public static List<TestFunction> scheduleCallFunctions() {
        return Stream.of(
                        // long zero
                        asHeadlongAddress(666),
                        // non long zero
                        asHeadlongAddress(Address.fromHexString(NON_LONG_ZERO_ADDRESS)))
                .flatMap(e -> Stream.of(
                        new TestFunction(
                                Bytes.wrapByteBuffer(ScheduleCallTranslator.SCHEDULE_CALL.encodeCall(Tuple.of(
                                        e,
                                        BigInteger.valueOf(1000),
                                        BigInteger.valueOf(1_000_000),
                                        BigInteger.valueOf(0),
                                        new byte[] {0}))),
                                OWNER_BESU_ADDRESS,
                                OWNER_BESU_ADDRESS),
                        new TestFunction(
                                Bytes.wrapByteBuffer(
                                        ScheduleCallTranslator.SCHEDULE_CALL_WITH_PAYER.encodeCall(Tuple.of(
                                                e,
                                                APPROVED_HEADLONG_ADDRESS,
                                                BigInteger.valueOf(1000),
                                                BigInteger.valueOf(1_000_000),
                                                BigInteger.valueOf(0),
                                                new byte[] {0}))),
                                APPROVED_HEADLONG_ADDRESS,
                                APPROVED_BESU_ADDRESS),
                        new TestFunction(
                                Bytes.wrapByteBuffer(
                                        ScheduleCallTranslator.EXECUTE_CALL_ON_PAYER_SIGNATURE.encodeCall(Tuple.of(
                                                e,
                                                APPROVED_HEADLONG_ADDRESS,
                                                BigInteger.valueOf(1000),
                                                BigInteger.valueOf(1_000_000),
                                                BigInteger.valueOf(0),
                                                new byte[] {0}))),
                                APPROVED_HEADLONG_ADDRESS,
                                APPROVED_BESU_ADDRESS)))
                .toList();
    }

    @ParameterizedTest
    @MethodSource("scheduleCallFunctions")
    void testAttempt(TestFunction data) {
        given(nativeOperations.getAccount(payerId)).willReturn(B_CONTRACT);
        if (data.senderParameter() instanceof Address) {
            given(addressIdConverter.convertSender(data.senderAddress())).willReturn(payerId);
        } else {
            given(addressIdConverter.convertSender(data.senderAddress())).willReturn(payerId);
            given(addressIdConverter.convert((com.esaulpaugh.headlong.abi.Address) data.senderParameter()))
                    .willReturn(payerId);
        }
        given(verificationStrategies.activatingOnlyContractKeysFor(data.senderAddress(), false, nativeOperations))
                .willReturn(verificationStrategy);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(HederaTestConfigBuilder.createConfig());
        // when:
        attempt = createHssCallAttempt(data.input(), data.senderAddress(), false, configuration, List.of(subject));
        // then:
        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(DispatchForResponseCodeHssCall.class);
    }

    @Test
    void testAttemptWrongSelector() {
        // given
        given(nativeOperations.getAccount(payerId)).willReturn(B_CONTRACT);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(payerId);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(HederaTestConfigBuilder.createConfig());
        // when:
        attempt = createHssCallAttempt(Bytes.wrap("wrongSelector".getBytes()), false, configuration, List.of(subject));
        // then:
        assertThrows(IllegalStateException.class, () -> subject.callFrom(attempt));
    }

    @Test
    void testGasRequirement() {
        // given:
        long expectedGas = 1000_000L;
        when(gasCalculator.gasRequirement(transactionBody, DispatchType.SCHEDULE_CREATE_CONTRACT_CALL, payerId))
                .thenReturn(expectedGas);
        // when:
        long gas = ScheduleCallTranslator.gasRequirement(transactionBody, gasCalculator, mockEnhancement(), payerId);
        // then:
        assertEquals(expectedGas, gas);
    }
}
