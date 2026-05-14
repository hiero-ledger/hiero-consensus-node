// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;

/**
 * Supports parameterized testing of {@link MerkleDbDataSource} with
 * both fixed- and variable-size data.
 *
 * <p>Used with JUnit's 'org.junit.jupiter.params.provider.EnumSource' annotation.
 */
public enum TestType {

    /** Parameterizes a test with fixed-size key and fixed-size data. */
    long_fixed,
    /** Parameterizes a test with fixed-size key and variable-size data. */
    long_variable,
    /** Parameterizes a test with fixed-size complex key and fixed-size data. */
    longLong_fixed,
    /** Parameterizes a test with fixed-size complex key and variable-size data. */
    longLong_variable,
    /** Parameterizes a test with variable-size key and fixed-size data. */
    variable_fixed,
    /** Parameterizes a test with variable-size key and variable-size data. */
    variable_variable;

    public DataTypeConfig dataType() {
        return new DataTypeConfig(this);
    }

    public static class DataTypeConfig {

        private final TestType testType;

        public DataTypeConfig(TestType testType) {
            this.testType = testType;
        }

        public Bytes createVirtualLongKey(final int i) {
            return switch (testType) {
                case longLong_fixed, longLong_variable -> ExampleLongLongKey.longToKey(i);
                case variable_fixed, variable_variable -> ExampleVariableKey.longToKey(i);
                default -> ExampleLongKey.longToKey(i);
            };
        }

        public ExampleByteArrayVirtualValue createVirtualValue(final int i) {
            return switch (testType) {
                case long_variable, longLong_variable, variable_variable -> new ExampleVariableValue(i);
                default -> new ExampleFixedValue(i);
            };
        }

        public Codec<? extends ExampleByteArrayVirtualValue> getCodec() {
            return switch (testType) {
                case long_fixed, longLong_fixed, variable_fixed -> ExampleFixedValue.CODEC;
                case long_variable, longLong_variable, variable_variable -> ExampleVariableValue.CODEC;
            };
        }

        @SuppressWarnings("rawtypes")
        public VirtualLeafBytes createVirtualLeafRecord(final int i) {
            return createVirtualLeafRecord(i, i, i);
        }

        @SuppressWarnings("rawtypes")
        public VirtualLeafBytes createVirtualLeafRecord(final long path, final int i, final int valueIndex) {
            return switch (testType) {
                case long_variable, longLong_variable, variable_variable ->
                    new VirtualLeafBytes<>(
                            path,
                            createVirtualLongKey(i),
                            new ExampleVariableValue(valueIndex),
                            ExampleVariableValue.CODEC);
                default ->
                    new VirtualLeafBytes<>(
                            path, createVirtualLongKey(i), new ExampleFixedValue(valueIndex), ExampleFixedValue.CODEC);
            };
        }
    }
}
