// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.hasschedulecapacity;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.hasschedulecapacity.HasScheduleCapacityCallStub;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.hasschedulecapacity.HasScheduleCapacityTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.schedulecall.ScheduleCallTranslatorTest.TestSelector;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import java.math.BigInteger;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

class HasScheduleCapacityTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HssCallAttempt attempt;

    @Mock
    private Configuration configuration;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private ContractMetrics contractMetrics;

    private HasScheduleCapacityTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new HasScheduleCapacityTranslator(systemContractMethodRegistry, contractMetrics);
    }

    private static List<TestSelector> hasScheduleCapacitySelectors() {
        return List.of(
                new TestSelector(Bytes.wrap(HasScheduleCapacityTranslator.HAS_SCHEDULE_CAPACITY.selector()), true, true),
                new TestSelector(Bytes.wrap("wrongSelector".getBytes()), true, false),
                new TestSelector(Bytes.wrap(HasScheduleCapacityTranslator.HAS_SCHEDULE_CAPACITY.selector()), false, false)
        );
    }

    @ParameterizedTest
    @MethodSource("hasScheduleCapacitySelectors")
    public void testConfig(final TestSelector data) {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractHasScheduleCapacityEnabled()).willReturn(data.enabled());

        // when:
        attempt = createHssCallAttempt(
                data.selector(),
                false,
                configuration,
                List.of(subject));

        // then:
        assertEquals(data.present(), subject.identifyMethod(attempt).isPresent());
    }

    @Test
    void testAttempt() {
        // when:
        Bytes input = Bytes.wrapByteBuffer(HasScheduleCapacityTranslator.HAS_SCHEDULE_CAPACITY.encodeCall(
                Tuple.of(BigInteger.valueOf(1000), BigInteger.valueOf(1000_000))));
        attempt = createHssCallAttempt(input, false, configuration, List.of(subject));
        // then:
        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(HasScheduleCapacityCallStub.class);
    }

    @Test
    void testAttemptWrongSelector() {
        // when:
        attempt = createHssCallAttempt(Bytes.wrap("wrongSelector".getBytes()), false, configuration, List.of(subject));
        // then:
        assertThrows(IllegalArgumentException.class, () -> subject.callFrom(attempt));
    }
}
