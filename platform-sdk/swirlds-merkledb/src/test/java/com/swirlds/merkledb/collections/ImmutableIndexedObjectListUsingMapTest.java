// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ImmutableIndexedObjectListUsingMapTest extends ImmutableIndexedObjectListUsingArrayTest {

    @Override
    public ImmutableIndexedObjectList<TestIndexedObject> factoryForReaderToTest(final TestIndexedObject[] objects) {
        return new ImmutableIndexedObjectListUsingMap<>(objects);
    }
}
