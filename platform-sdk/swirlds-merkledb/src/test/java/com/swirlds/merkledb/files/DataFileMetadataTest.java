// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCompactor.INITIAL_COMPACTION_LEVEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DataFileMetadataTest {

    @Test
    void equalsIncorporatesAllFields() {
        final long dataItemCount = 3;
        final int index = 4;
        final Instant creationDate = Instant.ofEpochSecond(1_234_567L);
        final int compactionLevel = INITIAL_COMPACTION_LEVEL;

        final DataFileMetadata base = new DataFileMetadata(dataItemCount, index, creationDate, compactionLevel);
        final DataFileMetadata differentItemCount =
                new DataFileMetadata(dataItemCount + 1, index, creationDate, compactionLevel);
        final DataFileMetadata differentIndex =
                new DataFileMetadata(dataItemCount, index + 1, creationDate, compactionLevel);
        final DataFileMetadata differentCreationDate =
                new DataFileMetadata(dataItemCount, index, creationDate.plusSeconds(1), compactionLevel);
        final DataFileMetadata differentCompactionLevel =
                new DataFileMetadata(dataItemCount, index, creationDate, compactionLevel + 1);
        final DataFileMetadata otherButEqual =
                new DataFileMetadata(dataItemCount, index, creationDate, compactionLevel);

        assertEquals(base, otherButEqual, "Equivalent metadata are equal");
        assertNotEquals(base, differentItemCount, "Different item counts are unequal");
        assertNotEquals(base, differentIndex, "Different indexes are unequal");
        assertNotEquals(base, differentCreationDate, "Different creation dates are unequal");
        assertNotEquals(base, differentCompactionLevel, "Different compaction level are unequal");
        assertNotEquals(base, new Object(), "Radically different objects are unequal");
    }
}
