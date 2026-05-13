// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures.files;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.test.fixtures.ExampleLongKey;
import com.swirlds.merkledb.test.fixtures.ExampleVariableKey;

/**
 * Supports parameterized testing of {@link MerkleDbDataSource} with both fixed- and variable-size
 * data.
 *
 * <p>Used with JUnit's {@link org.junit.jupiter.params.provider.EnumSource} annotation.
 */
public enum FilesTestType {

    /** Parameterizes a test with fixed-size data. */
    fixed,

    /** Parameterizes a test with variable-size data. */
    variable;

    public Bytes createVirtualLongKey(final int i) {
        return switch (this) {
            case variable -> ExampleVariableKey.longToKey(i);
            default -> ExampleLongKey.longToKey(i);
        };
    }
}
