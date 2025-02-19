/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
        switch (this) {
            case fixed:
            default:
                return ExampleLongKey.longToKey(i);
            case variable:
                return ExampleVariableKey.longToKey(i);
        }
    }
}
