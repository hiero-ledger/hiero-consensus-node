/*
 * Copyright (C) 2018-2025 Hedera Hashgraph, LLC
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

package com.swirlds.virtualmap.internal.merkle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import org.junit.jupiter.api.Test;

class VirtualLeafNodeTest {

    @Test
    void copyNotSupported() {
        final VirtualLeafBytes<TestValue> leafRecord = new VirtualLeafBytes<>(1, Bytes.EMPTY, null);
        final VirtualLeafNode virtualLeaf = new VirtualLeafNode(leafRecord, null);
        assertThrows(UnsupportedOperationException.class, virtualLeaf::copy, "Copy is not supported");
    }

    @Test
    void toStringTest() {
        // Shameless test to cover toString. All I really care is it doesn't throw an NPE.
        final VirtualLeafBytes<TestValue> leafRecord = new VirtualLeafBytes<>(1, Bytes.EMPTY, null);
        final VirtualLeafNode leaf = new VirtualLeafNode(leafRecord, null);
        assertNotNull(leaf.toString(), "leaf should not have a null string");

        // a few addition tests that also just juice the coverage numbers
        assertEquals(leaf, leaf, "A VirtualNode should always be equal to itself.");
        assertNotEquals(leaf, leafRecord, "A VirtualNode should never be equal to a non-VirtualNode value.");
        assertEquals(leaf.hashCode(), leaf.hashCode(), "A VirtualNode's hashCode() should remain constant.");
    }
}
