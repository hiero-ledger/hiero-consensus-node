// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.dummy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.common.test.fixtures.merkle.dummy.SelfHashingDummyMerkleLeaf;
import java.io.IOException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class SelfHashingDummyMerkleLeafTest {

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("SelfHashingDummyMerkleLeaf is immutable after copy")
    void isImmutableAfterCopy() {
        final SelfHashingDummyMerkleLeaf leaf = new SelfHashingDummyMerkleLeaf("This is a copy test");
        leaf.copy();

        assertTrue(leaf.isImmutable(), "Copy has been called on the object");
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Serializes and Deserializes")
    void serializeAndDeserialize() throws IOException {
        final SelfHashingDummyMerkleLeaf leaf = new SelfHashingDummyMerkleLeaf("This is a serialize test");
        try (final InputOutputStream io = new InputOutputStream()) {
            leaf.serialize(io.getOutput());
            io.startReading();
            final SelfHashingDummyMerkleLeaf deserializedLeaf = new SelfHashingDummyMerkleLeaf();
            deserializedLeaf.deserialize(io.getInput(), deserializedLeaf.getVersion());
            assertEquals(leaf, deserializedLeaf, "Deserialized object should match serialized one");
        }
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Reflexive property for equals")
    void equalsAgainstItself() {
        final SelfHashingDummyMerkleLeaf leaf = new SelfHashingDummyMerkleLeaf("This is a leaf");
        assertEquals(leaf, leaf, "Validates equals against itself");
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Equals handles other types")
    void equalsAgainstNull() {
        final SelfHashingDummyMerkleLeaf leaf = new SelfHashingDummyMerkleLeaf("This is a leaf");
        final Object object = new Object();
        assertNotEquals(leaf, object, "Validates equals handles other types");
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    void equalsMatchesHashCode() {
        final SelfHashingDummyMerkleLeaf leaf = new SelfHashingDummyMerkleLeaf("This is a leaf");
        final SelfHashingDummyMerkleLeaf anotherLeaf = new SelfHashingDummyMerkleLeaf("This is a leaf");
        assertEquals(leaf, anotherLeaf, "The leaves have the same content");
        assertEquals(leaf.hashCode(), anotherLeaf.hashCode(), "The leaves should have the same hash code");
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    void hashCodeAfterNullValue() {
        final SelfHashingDummyMerkleLeaf leaf = new SelfHashingDummyMerkleLeaf("This is a leaf");
        leaf.setValue(null);

        assertEquals(0, leaf.hashCode(), "Default hash code if value is null");
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    void releaseInternalNode() {
        final SelfHashingDummyMerkleLeaf leaf = new SelfHashingDummyMerkleLeaf("This is a leaf");
        leaf.release();
        final Exception exception =
                assertThrows(IllegalStateException.class, leaf::destroyNode, "destroyNode is only allowed once");

        assertEquals(
                "This type of node should only be deleted once",
                exception.getMessage(),
                "Exception is due to its released state");
    }
}
