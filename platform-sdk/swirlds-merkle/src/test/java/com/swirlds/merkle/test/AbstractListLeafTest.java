// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.merkle.utility.AbstractListLeaf;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyListLeaf;
import java.util.LinkedList;
import java.util.List;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("AbstractListLeaf Test")
class AbstractListLeafTest {

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("AbstractListLeaf Test")
    void abstractListLeafTest() {
        AbstractListLeaf<SerializableLong> leaf = new DummyListLeaf();
        final List<SerializableLong> referenceList = new LinkedList<>();

        assertEquals(referenceList, leaf, "lists should be equal");
        AbstractListLeaf<SerializableLong> copy = leaf.copy();
        assertEquals(referenceList, copy, "lists should be equal");
        leaf.add(new SerializableLong(1234)); // this should not effect the copy
        assertEquals(referenceList, copy, "lists should be equal");

        leaf = new DummyListLeaf();

        for (int i = 0; i < 100; i++) {
            referenceList.add(new SerializableLong(i));
            leaf.add(new SerializableLong(i));
        }

        assertEquals(referenceList, leaf, "lists should be equal");
        copy = leaf.copy();
        assertEquals(referenceList, copy, "lists should be equal");
        leaf.add(new SerializableLong(1234)); // this should not effect the copy
        assertEquals(referenceList, copy, "lists should be equal");
    }
}
