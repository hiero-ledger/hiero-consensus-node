// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCompactor.INITIAL_COMPACTION_LEVEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DataFileMetadataTest {

    private static final DataFileMetadata BASE =
            new DataFileMetadata(4, Instant.ofEpochSecond(1_234_567L), INITIAL_COMPACTION_LEVEL);

    @Test
    void loadFromEmptyFile() throws IOException {
        final Path file = Files.createTempFile(null, null);
        try {
            assertThrows(IllegalArgumentException.class, () -> DataFileMetadata.readFromFile(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void compareExpectedAndActualSizeWrittenToFile() {
        BufferedData buffer = BufferedData.wrap(new byte[64]);
        BASE.writeTo(buffer);

        assertEquals(
                BASE.metadataSizeInBytes(), buffer.position(), "Number of bytes written should match calculated size");
    }

    @Test
    void sameObjectsEquality() {
        DataFileMetadata copy =
                new DataFileMetadata(BASE.getIndex(), BASE.getCreationDate(), BASE.getCompactionLevel());

        assertEquals(BASE, copy, "Equivalent metadata are equal");
        assertEquals(BASE.hashCode(), copy.hashCode(), "Equivalent metadata have equal hash code");
        assertEquals(BASE.toString(), copy.toString(), "Equivalent metadata have equal toString");
    }

    @ParameterizedTest
    @MethodSource("differentObjectsParameters")
    void differentObjectsInequality(Object actual, String diffMessage) {
        assertNotEquals(BASE, actual, diffMessage);
    }

    private static Stream<Arguments> differentObjectsParameters() {
        return Stream.of(
                Arguments.arguments(null, "Null is not equal to anything"),
                Arguments.arguments(new Object(), "Radically different objects are unequal"),
                Arguments.arguments(
                        new DataFileMetadata(BASE.getIndex() + 1, BASE.getCreationDate(), BASE.getCompactionLevel()),
                        "Different indexes are unequal"),
                Arguments.arguments(
                        new DataFileMetadata(
                                BASE.getIndex(), BASE.getCreationDate().plusSeconds(1), BASE.getCompactionLevel()),
                        "Different creation dates are unequal"),
                Arguments.arguments(
                        new DataFileMetadata(BASE.getIndex(), BASE.getCreationDate(), BASE.getCompactionLevel() + 1),
                        "Different compaction level are unequal"));
    }
}
