// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures.files;

import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.test.fixtures.ExampleFixedSizeDataSerializer;
import com.swirlds.merkledb.test.fixtures.ExampleLongKeyFixedSize;
import com.swirlds.merkledb.test.fixtures.ExampleLongKeyVariableSize;
import com.swirlds.merkledb.test.fixtures.ExampleLongLongKeyFixedSize;
import com.swirlds.merkledb.test.fixtures.ExampleLongLongKeyVariableSize;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.serialize.BaseSerializer;
import com.swirlds.virtualmap.serialize.KeySerializer;

/**
 * Supports parameterized testing of {@link MerkleDbDataSource} with both fixed- and variable-size
 * data.
 *
 * <p>Used with JUnit's {@link org.junit.jupiter.params.provider.EnumSource} annotation.
 */
public enum FilesTestType {
    /** Parameterizes a test with fixed-size data. */
    fixed(new ExampleFixedSizeDataSerializer(), new ExampleLongKeyFixedSize.Serializer()),
    /** Parameterizes a test with fixed-size data and a complex key. */
    fixedComplexKey(new ExampleFixedSizeDataSerializer(), new ExampleLongLongKeyFixedSize.Serializer()),
    /** Parameterizes a test with variable-size data. */
    variable(new ExampleVariableSizeDataSerializer(), new ExampleLongKeyVariableSize.Serializer()),
    /** Parameterizes a test with variable-size data and a complex key. */
    variableComplexKey(new ExampleVariableSizeDataSerializer(), new ExampleLongLongKeyVariableSize.Serializer());

    /** used by files package level tests */
    public final BaseSerializer<long[]> dataItemSerializer;

    public final KeySerializer keySerializer;

    FilesTestType(final BaseSerializer<long[]> dataItemSerializer, KeySerializer<? extends VirtualKey> keySerializer) {
        this.dataItemSerializer = dataItemSerializer;
        this.keySerializer = keySerializer;
    }

    public VirtualKey createVirtualLongKey(final int i) {
        switch (this) {
            case fixed:
            default:
                return new ExampleLongKeyFixedSize(i);
            case fixedComplexKey:
                return new ExampleLongLongKeyFixedSize(i);
            case variable:
                return new ExampleLongKeyVariableSize(i);
            case variableComplexKey:
                return new ExampleLongLongKeyVariableSize(i);
        }
    }
}
