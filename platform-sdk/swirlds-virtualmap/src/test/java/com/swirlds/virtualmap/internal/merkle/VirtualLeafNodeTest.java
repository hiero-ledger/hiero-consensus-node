// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VirtualLeafNodeTest {

    @BeforeAll
    public static void globalSetup() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructable(new ClassConstructorPair(VirtualLeafRecord.class, VirtualLeafRecord::new));
        registry.registerConstructable(new ClassConstructorPair(TestKey.class, TestKey::new));
        registry.registerConstructable(new ClassConstructorPair(TestValue.class, TestValue::new));
        registry.registerConstructable(new ClassConstructorPair(Hash.class, Hash::new));
    }

    @Test
    void copyNotSupported() {
        final VirtualLeafRecord<TestKey, TestValue> leafRecord = new VirtualLeafRecord<>(1, null, null);
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        final VirtualLeafNode<TestKey, TestValue> virtualLeaf = new VirtualLeafNode<>(leafRecord, null);
        assertThrows(UnsupportedOperationException.class, virtualLeaf::copy, "Copy is not supported");
    }

    @Test
    void toStringTest() {
        // Shameless test to cover toString. All I really care is it doesn't throw an NPE.
        final VirtualLeafRecord<TestKey, TestValue> leafRecord = new VirtualLeafRecord<>(1, null, null);
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        final VirtualLeafNode<TestKey, TestValue> leaf = new VirtualLeafNode<>(leafRecord, null);
        assertNotNull(leaf.toString(), "leaf should not have a null string");

        // a few addition tests that also just juice the coverage numbers
        assertEquals(leaf, leaf, "A VirtualNode should always be equal to itself.");
        assertNotEquals(leaf, leafRecord, "A VirtualNode should never be equal to a non-VirtualNode value.");
        assertEquals(leaf.hashCode(), leaf.hashCode(), "A VirtualNode's hashCode() should remain constant.");
    }
}
