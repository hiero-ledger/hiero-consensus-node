// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.MerkleDbStatistics;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import org.hiero.base.crypto.DigestType;

/**
 * Supports parameterized testing of {@link MerkleDbDataSource} with
 * both fixed- and variable-size data.
 *
 * <p>Used with JUnit's 'org.junit.jupiter.params.provider.EnumSource' annotation.
 */
public enum TestType {

    /** Parameterizes a test with fixed-size key and fixed-size data. */
    fixed_fixed(true),
    /** Parameterizes a test with fixed-size key and variable-size data. */
    fixed_variable(false),
    /** Parameterizes a test with fixed-size complex key and fixed-size data. */
    fixedComplex_fixed(true),
    /** Parameterizes a test with fixed-size complex key and variable-size data. */
    fixedComplex_variable(false),
    /** Parameterizes a test with variable-size key and fixed-size data. */
    variable_fixed(false),
    /** Parameterizes a test with variable-size key and variable-size data. */
    variable_variable(false),
    /** Parameterizes a test with variable-size complex key and fixed-size data. */
    variableComplex_fixed(false),
    /** Parameterizes a test with variable-size complex key and variable-size data. */
    variableComplex_variable(false);

    public final boolean fixedSize;

    private Metrics metrics = null;

    TestType(boolean fixedSize) {
        this.fixedSize = fixedSize;
    }

    public <K extends VirtualKey, V extends VirtualValue> DataTypeConfig<K, V> dataType() {
        return new DataTypeConfig<>(this);
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

    @SuppressWarnings({"unchecked", "rawtypes", "unused"})
    public class DataTypeConfig<K extends VirtualKey, V extends VirtualValue> {

        private final TestType testType;
        private final KeySerializer<? extends VirtualKey> keySerializer;
        private final ValueSerializer<? extends ExampleByteArrayVirtualValue> valueSerializer;

        public DataTypeConfig(TestType testType) {
            this.testType = testType;
            this.keySerializer = createKeySerializer();
            this.valueSerializer = createValueSerializer();
        }

        public KeySerializer<? extends VirtualKey> getKeySerializer() {
            return keySerializer;
        }

        public ValueSerializer<? extends ExampleByteArrayVirtualValue> getValueSerializer() {
            return valueSerializer;
        }

        private KeySerializer<? extends VirtualKey> createKeySerializer() {
            switch (testType) {
                default:
                case fixed_fixed:
                case fixed_variable:
                    return new ExampleLongKeyFixedSize.Serializer();
                case fixedComplex_fixed:
                case fixedComplex_variable:
                    return new ExampleLongLongKeyFixedSize.Serializer();
                case variable_fixed:
                case variable_variable:
                    return new ExampleLongKeyVariableSize.Serializer();
                case variableComplex_fixed:
                case variableComplex_variable:
                    return new ExampleLongLongKeyVariableSize.Serializer();
            }
        }

        private ValueSerializer<? extends ExampleByteArrayVirtualValue> createValueSerializer() {
            switch (testType) {
                default:
                case fixed_fixed:
                case fixedComplex_fixed:
                case variable_fixed:
                case variableComplex_fixed:
                    return new ExampleFixedSizeVirtualValueSerializer();
                case fixed_variable:
                case fixedComplex_variable:
                case variable_variable:
                case variableComplex_variable:
                    return new ExampleVariableSizeVirtualValueSerializer();
            }
        }

        public VirtualKey createVirtualLongKey(final int i) {
            switch (testType) {
                default:
                case fixed_fixed:
                case fixed_variable:
                    return new ExampleLongKeyFixedSize(i);
                case fixedComplex_fixed:
                case fixedComplex_variable:
                    return new ExampleLongLongKeyFixedSize(i);
                case variable_fixed:
                case variable_variable:
                    return new ExampleLongKeyVariableSize(i);
                case variableComplex_fixed:
                case variableComplex_variable:
                    return new ExampleLongLongKeyVariableSize(i);
            }
        }

        public ExampleByteArrayVirtualValue createVirtualValue(final int i) {
            switch (testType) {
                default:
                case fixed_fixed:
                case fixedComplex_fixed:
                case variable_fixed:
                case variableComplex_fixed:
                    return new ExampleFixedSizeVirtualValue(i);
                case fixed_variable:
                case fixedComplex_variable:
                case variable_variable:
                case variableComplex_variable:
                    return new ExampleVariableSizeVirtualValue(i);
            }
        }

        /**
         * Get the file size for a file created in DataFileLowLevelTest.createFile test. Values here are measured values
         * from a known good test run.
         */
        public long getDataFileLowLevelTestFileSize() {
            switch (testType) {
                default:
                case fixed_fixed:
                case fixed_variable:
                case fixedComplex_fixed:
                case fixedComplex_variable:
                case variable_fixed:
                case variable_variable:
                case variableComplex_fixed:
                case variableComplex_variable:
                    return 24576L;
            }
        }

        public boolean hasKeyToPathStore() {
            return (keySerializer.getSerializedSize() != Long.BYTES);
        }

        public MerkleDbDataSource createDataSource(
                final Path dbPath,
                final String name,
                final int size,
                final long hashesRamToDiskThreshold,
                final boolean enableMerging,
                boolean preferDiskBasedIndexes)
                throws IOException {
            final MerkleDb database = MerkleDb.getInstance(dbPath, CONFIGURATION);
            final MerkleDbTableConfig tableConfig =
                    new MerkleDbTableConfig((short) 1, DigestType.SHA_384, size, hashesRamToDiskThreshold);
            MerkleDbDataSource dataSource = database.createDataSource(name, tableConfig, enableMerging);
            dataSource.registerMetrics(getMetrics());
            return dataSource;
        }

        public MerkleDbDataSource getDataSource(final Path dbPath, final String name, final boolean enableMerging)
                throws IOException {
            final MerkleDb database = MerkleDb.getInstance(dbPath, CONFIGURATION);
            return database.getDataSource(name, enableMerging);
        }

        public VirtualHashRecord createVirtualInternalRecord(final int i) {
            return new VirtualHashRecord(i, MerkleDbTestUtils.hash(i));
        }

        public VirtualLeafRecord<VirtualKey, ExampleByteArrayVirtualValue> createVirtualLeafRecord(final int i) {
            return createVirtualLeafRecord(i, i, i);
        }

        public VirtualLeafRecord<VirtualKey, ExampleByteArrayVirtualValue> createVirtualLeafRecord(
                final long path, final int i, final int valueIndex) {

            switch (testType) {
                default:
                case fixed_fixed:
                case fixedComplex_fixed:
                case variable_fixed:
                case variableComplex_fixed:
                    return new VirtualLeafRecord<>(
                            path, createVirtualLongKey(i), new ExampleFixedSizeVirtualValue(valueIndex));
                case fixed_variable:
                case fixedComplex_variable:
                case variable_variable:
                case variableComplex_variable:
                    return new VirtualLeafRecord<>(
                            path, createVirtualLongKey(i), new ExampleVariableSizeVirtualValue(valueIndex));
            }
        }
    }
}
