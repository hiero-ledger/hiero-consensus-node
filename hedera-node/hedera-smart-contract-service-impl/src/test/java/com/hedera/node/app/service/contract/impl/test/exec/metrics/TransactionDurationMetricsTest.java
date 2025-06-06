// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.metrics.TransactionDurationMetrics;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
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
class TransactionDurationMetricsTest {

    private static final long DEFAULT_NODE_ID = 3;
    private Metrics metrics;
    private TransactionDurationMetrics subject;

    @Mock
    private HederaEvmTransaction transaction1;

    @Mock
    private AccountID sender1;

    @Mock
    private HederaEvmTransaction transaction2;

    @Mock
    private AccountID sender2;

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

        subject = new TransactionDurationMetrics(metrics);
    }

    @Test
    void recordsAndRetrievesTransactionDuration() {
        // Given
        final long duration = 100L;
        prepareTransactionMocks();
        // When
        subject.recordTransactionDuration(transaction1, duration);

        // Then
        assertThat(subject.getDuration(transaction1)).isEqualTo(duration);
    }

    @Test
    void returnsZeroForNonExistentTransaction() {
        // Given
        final HederaEvmTransaction nonExistentTransaction = transaction2;
        // When
        final long duration = subject.getDuration(nonExistentTransaction);

        // Then
        assertThat(duration).isZero();
    }

    @Test
    void handlesMultipleTransactions() {
        // Given
        final long duration1 = 100L;
        final long duration2 = 200L;
        prepareTransactionMocks();
        // When
        subject.recordTransactionDuration(transaction1, duration1);
        subject.recordTransactionDuration(transaction2, duration2);

        // Then
        assertThat(subject.getDuration(transaction1)).isEqualTo(duration1);
        assertThat(subject.getDuration(transaction2)).isEqualTo(duration2);
    }

    @Test
    void updatesExistingTransactionDuration() {
        // Given
        final long initialDuration = 100L;
        final long updatedDuration = 200L;
        prepareTransactionMocks();
        // When
        subject.recordTransactionDuration(transaction1, initialDuration);
        subject.recordTransactionDuration(transaction1, updatedDuration);

        // Then
        assertThat(subject.getDuration(transaction1)).isEqualTo(updatedDuration);
    }

    private void prepareTransactionMocks() {
        given(transaction1.senderId()).willReturn(sender1);
        given(transaction1.nonce()).willReturn(1L);
        lenient().when(transaction2.senderId()).thenReturn(sender2);
        lenient().when(transaction2.nonce()).thenReturn(2L);
    }
}
