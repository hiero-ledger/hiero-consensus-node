// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.within;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.metrics.OpsDurationMetrics;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.metrics.api.Metrics;
import java.util.concurrent.Executors;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpsDurationMetricsTest {
    private static final long DEFAULT_NODE_ID = 3;
    private Metrics metrics;
    private OpsDurationMetrics subject;

    @Mock
    private SystemContractMethod method1;

    @Mock
    private SystemContractMethod method2;

    @BeforeEach
    void setUp() {
        final MetricsConfig metricsConfig =
                HederaTestConfigBuilder.createConfig().getConfigData(MetricsConfig.class);
        metrics = new DefaultPlatformMetrics(
                NodeId.of(DEFAULT_NODE_ID),
                new MetricKeyRegistry(),
                Executors.newSingleThreadScheduledExecutor(),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        subject = new OpsDurationMetrics(metrics);
    }

    @Test
    void systemContractMetricsAreRecordedAndRetrieved() {
        given(method1.methodName()).willReturn("method1");
        given(method2.methodName()).willReturn("method2");
        subject.recordSystemContractOpsDuration(method1, 100L);
        subject.recordSystemContractOpsDuration(method1, 200L);
        subject.recordSystemContractOpsDuration(method2, 300L);
        assertThat(subject.getAverageSystemContractOpsDuration(method1)).isCloseTo(150.0, within(5.0));
        assertThat(subject.getSystemContractOpsDurationCount(method1)).isEqualTo(2);
        assertThat(subject.getTotalSystemContractOpsDuration(method1)).isEqualTo(300L);
        assertThat(subject.getAverageSystemContractOpsDuration(method2)).isEqualTo(300.0);
        assertThat(subject.getSystemContractOpsDurationCount(method2)).isEqualTo(1);
        assertThat(subject.getTotalSystemContractOpsDuration(method2)).isEqualTo(300L);
    }

    @Test
    void opCodeMetricsAreRecordedAndRetrieved() {
        int opCode1 = 1;
        int opCode2 = 2;
        subject.recordOpCodeOpsDuration(opCode1, 100L);
        subject.recordOpCodeOpsDuration(opCode1, 200L);
        subject.recordOpCodeOpsDuration(opCode2, 300L);
        assertThat(subject.getAverageOpCodeOpsDuration(opCode1)).isCloseTo(150.0, within(5.0));
        assertThat(subject.getOpCodeOpsDurationCount(opCode1)).isEqualTo(2);
        assertThat(subject.getTotalOpCodeOpsDuration(opCode1)).isEqualTo(300L);
        assertThat(subject.getAverageOpCodeOpsDuration(opCode2)).isEqualTo(300.0);
        assertThat(subject.getOpCodeOpsDurationCount(opCode2)).isEqualTo(1);
        assertThat(subject.getTotalOpCodeOpsDuration(opCode2)).isEqualTo(300L);
    }

    @Test
    void precompileMetricsAreRecordedAndRetrieved() {
        String precompile1 = "precompile1";
        String precompile2 = "precompile2";
        subject.recordPrecompileOpsDuration(precompile1, 100L);
        subject.recordPrecompileOpsDuration(precompile1, 200L);
        subject.recordPrecompileOpsDuration(precompile2, 300L);
        assertThat(subject.getAveragePrecompileOpsDuration(precompile1)).isCloseTo(150.0, within(5.0));
        assertThat(subject.getPrecompileOpsDurationCount(precompile1)).isEqualTo(2);
        assertThat(subject.getTotalPrecompileOpsDuration(precompile1)).isEqualTo(300L);
        assertThat(subject.getAveragePrecompileOpsDuration(precompile2)).isEqualTo(300.0);
        assertThat(subject.getPrecompileOpsDurationCount(precompile2)).isEqualTo(1);
        assertThat(subject.getTotalPrecompileOpsDuration(precompile2)).isEqualTo(300L);
    }

    @Test
    void transactionOpsDurationMetricsAreRecordedAndRetrieved() {
        subject.recordTxnTotalOpsDuration(100L);
        subject.recordTxnTotalOpsDuration(200L);
        assertThat(subject.getAverageTransactionOpsDuration()).isCloseTo(150.0, within(5.0));
        assertThat(subject.getTransactionOpsDurationCount()).isEqualTo(2);
        assertThat(subject.getTotalTransactionOpsDuration()).isEqualTo(300L);
    }

    @Test
    void throttledTransactionMetricsAreRecordedAndRetrieved() {
        assertThat(subject.getTransactionsThrottledByOpsDurationCount()).isZero();
        subject.recordTransactionsThrottledByOpsDuration();
        assertThat(subject.getTransactionsThrottledByOpsDurationCount()).isEqualTo(1);
        subject.recordTransactionsThrottledByOpsDuration();
        assertThat(subject.getTransactionsThrottledByOpsDurationCount()).isEqualTo(2);
    }
}
