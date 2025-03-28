// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.MerkleDbStatistics.STAT_CATEGORY;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.nextInt;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MerkleDbStatisticsTest {

    private Configuration configuration;

    private static final String LABEL = "LaBeL";

    private MetricsConfig metricsConfig;
    private MerkleDbStatistics statistics;
    private Metrics metrics;
    private int compactionLevel;

    @BeforeEach
    void setupService() {
        configuration = new TestConfigBuilder().getOrCreateConfig();
        metricsConfig = configuration.getConfigData(MetricsConfig.class);

        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        metrics = new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        statistics = new MerkleDbStatistics(configuration.getConfigData(MerkleDbConfig.class), LABEL);
        statistics.registerMetrics(metrics);
        compactionLevel = randomCompactionLevel();
    }

    @Test
    void testInitialState() {
        verifyMetricsDoesntThrow(statistics);
    }

    @Test
    public void testMetricsDoesntThrowBeforeRegister() {
        MerkleDbStatistics notRegisteredStatistics =
                new MerkleDbStatistics(configuration.getConfigData(MerkleDbConfig.class), LABEL);
        verifyMetricsDoesntThrow(notRegisteredStatistics);
    }

    private void verifyMetricsDoesntThrow(MerkleDbStatistics statistics) {
        assertDoesNotThrow(statistics::countHashReads);
        assertDoesNotThrow(statistics::countLeafReads);
        assertDoesNotThrow(statistics::countLeafKeyReads);
        assertDoesNotThrow(() -> statistics.setHashesStoreFileCount(42));
        assertDoesNotThrow(() -> statistics.setHashesStoreFileSizeMb(31415));
        assertDoesNotThrow(() -> statistics.setLeafKeysStoreFileCount(42));
        assertDoesNotThrow(() -> statistics.setLeafKeysStoreFileSizeMb(31415));
        assertDoesNotThrow(() -> statistics.setLeavesStoreFileCount(42));
        assertDoesNotThrow(() -> statistics.setLeavesStoreFileSizeMb(31415));
        assertDoesNotThrow(() -> statistics.setTotalFileSizeMb(314159));
        assertDoesNotThrow(() -> statistics.setHashesStoreCompactionTimeMs(compactionLevel, 314));
        assertDoesNotThrow(() -> statistics.setHashesStoreCompactionSavedSpaceMb(compactionLevel, Math.PI));
        assertDoesNotThrow(() -> statistics.setHashesStoreFileSizeByLevelMb(compactionLevel, Math.PI));
        assertDoesNotThrow(() -> statistics.setLeavesStoreCompactionTimeMs(compactionLevel, 314));
        assertDoesNotThrow(() -> statistics.setLeavesStoreCompactionSavedSpaceMb(compactionLevel, Math.PI));
        assertDoesNotThrow(() -> statistics.setLeavesStoreFileSizeByLevelMb(compactionLevel, Math.PI));
        assertDoesNotThrow(() -> statistics.setLeafKeysStoreCompactionTimeMs(compactionLevel, 314));
        assertDoesNotThrow(() -> statistics.setLeafKeysStoreCompactionSavedSpaceMb(compactionLevel, Math.PI));
        assertDoesNotThrow(() -> statistics.setLeafKeysStoreFileSizeByLevelMb(compactionLevel, Math.PI));
        assertDoesNotThrow(() -> statistics.setOffHeapHashesIndexMb(42));
        assertDoesNotThrow(() -> statistics.setOffHeapLeavesIndexMb(42));
        assertDoesNotThrow(() -> statistics.setOffHeapObjectKeyBucketsIndexMb(42));
        assertDoesNotThrow(() -> statistics.setOffHeapHashesListMb(42));
        assertDoesNotThrow(() -> statistics.setOffHeapDataSourceMb(42));
    }

    @Test
    void testConstructorWithNullParameter() {
        assertThrows(
                NullPointerException.class,
                () -> new MerkleDbStatistics(configuration.getConfigData(MerkleDbConfig.class), null));
    }

    @Test
    void testRegisterWithNullParameter() {
        // given
        final MerkleDbStatistics statistics =
                new MerkleDbStatistics(configuration.getConfigData(MerkleDbConfig.class), LABEL);

        // then
        assertThrows(NullPointerException.class, () -> statistics.registerMetrics(null));
    }

    private Metric getMetric(final String section, final String suffix) {
        return getMetric(metrics, section, suffix);
    }

    private Metric getMetric(final Metrics metrics, final String section, final String suffix) {
        return metrics.getMetric(STAT_CATEGORY, "ds_" + section + suffix);
    }

    private static void assertValueSet(final Metric metric) {
        assertNotEquals(0.0, metric.get(VALUE));
    }

    @Test
    void testCycleInternalNodeReadsPerSecond() {
        // given
        final Metric metric = getMetric("reads_", "hashes_" + LABEL);
        // when
        statistics.countHashReads();
        // then
        assertValueSet(metric);
    }

    @Test
    void testCycleLeafByKeyReadsPerSecond() {
        // given
        final Metric metric = getMetric("reads_", "leaves_" + LABEL);
        // when
        statistics.countLeafReads();
        // then
        assertValueSet(metric);
    }

    @Test
    void testCycleLeafKeyReadsPerSecond() {
        // given
        final Metric metric = getMetric("reads_", "leafKeys_" + LABEL);
        // when
        statistics.countLeafKeyReads();
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetHashesStoreFileCount() {
        // given
        final Metric metric = getMetric("files_", "hashesStoreFileCount_" + LABEL);
        // when
        statistics.setHashesStoreFileCount(42);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetHashesStoreTotalFileSizeMb() {
        // given
        final Metric metric = getMetric("files_", "hashesStoreFileSizeMb_" + LABEL);
        // when
        statistics.setHashesStoreFileSizeMb(42);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetLeavesStoreFileCount() {
        // given
        final Metric metric = getMetric("files_", "leavesStoreFileCount_" + LABEL);
        // when
        statistics.setLeavesStoreFileCount(42);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetLeavesStoreTotalFileSizeMb() {
        // given
        final Metric metric = getMetric("files_", "leavesStoreFileSizeMb_" + LABEL);
        // when
        statistics.setLeavesStoreFileSizeMb(31415);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetLeafKeysStoreFileCount() {
        // given
        statistics = new MerkleDbStatistics(configuration.getConfigData(MerkleDbConfig.class), LABEL);
        statistics.registerMetrics(metrics);
        final Metric metric = getMetric("files_", "leafKeysStoreFileCount_" + LABEL);
        // when
        statistics.setLeafKeysStoreFileCount(42);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetLeafKeysStoreTotalFileSizeMb() {
        // given
        statistics = new MerkleDbStatistics(configuration.getConfigData(MerkleDbConfig.class), LABEL);
        statistics.registerMetrics(metrics);
        final Metric metric = getMetric("files_", "leafKeysStoreFileSizeMb_" + LABEL);
        // when
        statistics.setLeafKeysStoreFileSizeMb(31415);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetHashesStoreMergeTime() {
        // given
        final Metric metric = getMetric("compactions_level_" + compactionLevel, "_hashesTimeMs_" + LABEL);
        // when
        statistics.setHashesStoreCompactionTimeMs(compactionLevel, 31415);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetHashesStoreSavedSpace() {
        // given
        final Metric metric = getMetric("compactions_level_" + compactionLevel, "_hashesSavedSpaceMb_" + LABEL);
        // when
        statistics.setHashesStoreCompactionSavedSpaceMb(compactionLevel, Math.PI);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetHashesStoreFileSizeByLevel() {
        // given
        final Metric metric = getMetric("files_level_" + compactionLevel, "_hashesFileSizeByLevelMb_" + LABEL);
        // when
        statistics.setHashesStoreFileSizeByLevelMb(compactionLevel, Math.PI);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetLeafKeysStoreMergeTime() {
        // given
        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        final Metrics metrics = new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        final MerkleDbStatistics statistics =
                new MerkleDbStatistics(configuration.getConfigData(MerkleDbConfig.class), LABEL);
        statistics.registerMetrics(metrics);
        final Metric metric = getMetric(metrics, "compactions_level_" + compactionLevel, "_leafKeysTimeMs_" + LABEL);
        // when
        statistics.setLeafKeysStoreCompactionTimeMs(compactionLevel, 31415);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetLeafKeysStoreSavedSpace() {
        // given
        final Metric metric = getMetric("compactions_level_" + compactionLevel, "_leafKeysSavedSpaceMb_" + LABEL);
        // when
        statistics.setLeafKeysStoreCompactionSavedSpaceMb(compactionLevel, Math.PI);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetLeafKeysStoreFileSizeByLevel() {
        // given
        final Metric metric = getMetric("files_level_" + compactionLevel, "_leafKeysFileSizeByLevelMb_" + LABEL);
        // when
        statistics.setLeafKeysStoreFileSizeByLevelMb(compactionLevel, Math.PI);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetLeavesStoreMergeTime() {
        // given
        final Metric metric = getMetric("compactions_level_" + compactionLevel, "_leavesTimeMs_" + LABEL);
        // when
        statistics.setLeavesStoreCompactionTimeMs(compactionLevel, 31415);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetLeavesStoreSavedSpace() {
        // given
        final Metric metric = getMetric("compactions_level_" + compactionLevel, "_leavesSavedSpaceMb_" + LABEL);
        // when
        statistics.setLeavesStoreCompactionSavedSpaceMb(compactionLevel, Math.PI);
        // then
        assertValueSet(metric);
    }

    @Test
    void testSetLeavesStoreFileSizeByLevel() {
        // given
        final Metric metric = getMetric("files_level_" + compactionLevel, "_leavesFileSizeByLevelMb_" + LABEL);
        // when
        statistics.setLeavesStoreFileSizeByLevelMb(compactionLevel, Math.PI);
        // then
        assertValueSet(metric);
    }

    @Test
    void testOffHeapHashesIndex() {
        // given
        final Metric metric = getMetric("offheap_", "hashesIndexMb_" + LABEL);
        // when
        statistics.setOffHeapHashesIndexMb(42);
        // then
        assertValueSet(metric);
    }

    @Test
    void testOffHeapLeavesIndex() {
        // given
        final Metric metric = getMetric("offheap_", "leavesIndexMb_" + LABEL);
        // when
        statistics.setOffHeapLeavesIndexMb(42);
        // then
        assertValueSet(metric);
    }

    @Test
    void testOffHeapObjectKeyBucketsIndex() {
        // given
        final Metric metric = getMetric("offheap_", "objectKeyBucketsIndexMb_" + LABEL);
        // when
        statistics.setOffHeapObjectKeyBucketsIndexMb(42);
        // then
        assertValueSet(metric);
    }

    @Test
    void testOffHeadDataSource() {
        // given
        final Metric metric = getMetric("offheap_", "dataSourceMb_" + LABEL);
        // when
        statistics.setOffHeapDataSourceMb(42);
        // then
        assertValueSet(metric);
    }

    @Test
    void testTableNameWithPeriod() {
        final String complexLabel = "service." + LABEL;
        final String expectedLabelSuffix = complexLabel.replace('.', '_');
        final MerkleDbStatistics stats =
                new MerkleDbStatistics(configuration.getConfigData(MerkleDbConfig.class), complexLabel);
        stats.registerMetrics(metrics);

        // given
        final Metric metric = getMetric("files_", "totalSizeMb_" + expectedLabelSuffix);
        // when
        stats.setTotalFileSizeMb(10);
        // then
        assertValueSet(metric);
    }

    private static int randomCompactionLevel() {
        return nextInt(1, 6);
    }
}
