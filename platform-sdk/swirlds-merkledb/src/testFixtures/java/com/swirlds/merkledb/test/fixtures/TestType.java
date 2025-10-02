// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.MerkleDbStatistics;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Supports parameterized testing of {@link MerkleDbDataSource} with
 * both fixed- and variable-size data.
 *
 * <p>Used with JUnit's 'org.junit.jupiter.params.provider.EnumSource' annotation.
 */
public enum TestType {

    /** Parameterizes a test with fixed-size key and fixed-size data. */
    long_fixed(true),
    /** Parameterizes a test with fixed-size key and variable-size data. */
    long_variable(false),
    /** Parameterizes a test with fixed-size complex key and fixed-size data. */
    longLong_fixed(true),
    /** Parameterizes a test with fixed-size complex key and variable-size data. */
    longLong_variable(false),
    /** Parameterizes a test with variable-size key and fixed-size data. */
    variable_fixed(false),
    /** Parameterizes a test with variable-size key and variable-size data. */
    variable_variable(false);

    public final boolean fixedSize;

    private Metrics metrics = null;

    TestType(boolean fixedSize) {
        this.fixedSize = fixedSize;
    }

    public DataTypeConfig dataType() {
        return new DataTypeConfig(this);
    }

    public Metrics getMetrics() {
        if (metrics == null) {
            final Configuration CONFIGURATION = new TestConfigBuilder().getOrCreateConfig();
            MetricsConfig metricsConfig = CONFIGURATION.getConfigData(MetricsConfig.class);

            final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
            when(registry.register(any(), any(), any())).thenReturn(true);
            metrics = new DefaultPlatformMetrics(
                    null,
                    registry,
                    mock(ScheduledExecutorService.class),
                    new PlatformMetricsFactoryImpl(metricsConfig),
                    metricsConfig);
            MerkleDbStatistics statistics =
                    new MerkleDbStatistics(CONFIGURATION.getConfigData(MerkleDbConfig.class), "test");
            statistics.registerMetrics(metrics);
        }
        return metrics;
    }

    public class DataTypeConfig {

        private final TestType testType;

        public DataTypeConfig(TestType testType) {
            this.testType = testType;
        }

        public Bytes createVirtualLongKey(final int i) {
            switch (testType) {
                default:
                case long_fixed:
                case long_variable:
                    return ExampleLongKey.longToKey(i);
                case longLong_fixed:
                case longLong_variable:
                    return ExampleLongLongKey.longToKey(i);
                case variable_fixed:
                case variable_variable:
                    return ExampleVariableKey.longToKey(i);
            }
        }

        public ExampleByteArrayVirtualValue createVirtualValue(final int i) {
            switch (testType) {
                default:
                case long_fixed:
                case longLong_fixed:
                case variable_fixed:
                    return new ExampleFixedValue(i);
                case long_variable:
                case longLong_variable:
                case variable_variable:
                    return new ExampleVariableValue(i);
            }
        }

        public Codec<? extends ExampleByteArrayVirtualValue> getCodec() {
            return switch (testType) {
                case long_fixed -> ExampleFixedValue.CODEC;
                case longLong_fixed -> ExampleFixedValue.CODEC;
                case variable_fixed -> ExampleFixedValue.CODEC;
                case long_variable -> ExampleVariableValue.CODEC;
                case longLong_variable -> ExampleVariableValue.CODEC;
                case variable_variable -> ExampleVariableValue.CODEC;
            };
        }

        /**
         * Get the file size for a file created in DataFileLowLevelTest.createFile test. Values here are measured values
         * from a known good test run.
         */
        public long getDataFileLowLevelTestFileSize() {
            switch (testType) {
                default:
                case long_fixed:
                case long_variable:
                case longLong_fixed:
                case longLong_variable:
                case variable_fixed:
                case variable_variable:
                    return 24576L;
            }
        }

        public MerkleDbDataSource createDataSource(
                final Path dbPath,
                final String name,
                final int size,
                final long hashesRamToDiskThreshold,
                final boolean enableMerging,
                boolean preferDiskBasedIndexes)
                throws IOException {
            MerkleDbDataSource dataSource = new MerkleDbDataSource(
                    dbPath, CONFIGURATION, name, size, hashesRamToDiskThreshold, enableMerging, preferDiskBasedIndexes);
            dataSource.registerMetrics(getMetrics());
            return dataSource;
        }

        public MerkleDbDataSource getDataSource(final Path dbPath, final String name, final boolean enableMerging)
                throws IOException {
            return new MerkleDbDataSource(dbPath, CONFIGURATION, name, enableMerging, false);
        }

        public VirtualHashRecord createVirtualInternalRecord(final int i) {
            return new VirtualHashRecord(i, MerkleDbTestUtils.hash(i));
        }

        @SuppressWarnings("rawtypes")
        public VirtualLeafBytes createVirtualLeafRecord(final int i) {
            return createVirtualLeafRecord(i, i, i);
        }

        @SuppressWarnings("rawtypes")
        public VirtualLeafBytes createVirtualLeafRecord(final long path, final int i, final int valueIndex) {
            switch (testType) {
                default:
                case long_fixed:
                case longLong_fixed:
                case variable_fixed:
                    return new VirtualLeafBytes<>(
                            path, createVirtualLongKey(i), new ExampleFixedValue(valueIndex), ExampleFixedValue.CODEC);
                case long_variable:
                case longLong_variable:
                case variable_variable:
                    return new VirtualLeafBytes<>(
                            path,
                            createVirtualLongKey(i),
                            new ExampleVariableValue(valueIndex),
                            ExampleVariableValue.CODEC);
            }
        }
    }
}
