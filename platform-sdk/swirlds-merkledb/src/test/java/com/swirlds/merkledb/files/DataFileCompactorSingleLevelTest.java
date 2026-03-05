// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataFileCompactorSingleLevelTest {

    private DataFileCollection dataFileCollection;
    private CASableLongIndex index;
    private TestDataFileCompactor compactor;

    @BeforeEach
    void setUp() {
        dataFileCollection = mock(DataFileCollection.class);
        index = mock(CASableLongIndex.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of());

        final MerkleDbConfig config = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        compactor = new TestDataFileCompactor(config, dataFileCollection, index);
    }

    @Test
    void compactSingleLevelCompactsSelectedFilesAtTargetLevel() throws IOException, InterruptedException {
        final DataFileReader levelTwoFile1 = mockReader(1, 2);
        final DataFileReader levelTwoFile2 = mockReader(2, 2);
        final DataFileReader otherFile = mockReader(3, 2);

        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(levelTwoFile1, levelTwoFile2, otherFile));

        final boolean compacted = compactor.compactSingleLevel(List.of(levelTwoFile1, levelTwoFile2), 3);

        assertTrue(compacted);
        assertEquals(3, compactor.getCapturedTargetLevel());
        assertEquals(List.of(levelTwoFile1, levelTwoFile2), compactor.getCapturedFiles());
    }

    @Test
    void compactSingleLevelWithEmptyListReturnsFalse() throws IOException, InterruptedException {
        final boolean compacted = compactor.compactSingleLevel(List.of(), 1);

        assertFalse(compacted);
        assertEquals(-1, compactor.getCapturedTargetLevel());
        assertTrue(compactor.getCapturedFiles().isEmpty());
    }

    @Test
    void compactSingleLevelLeavesNonCompactedFilesUntouched() throws IOException, InterruptedException {
        final DataFileReader selectedFile = mockReader(10, 1);
        final DataFileReader untouchedFile = mockReader(11, 1);

        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(selectedFile, untouchedFile));

        final boolean compacted = compactor.compactSingleLevel(List.of(selectedFile), 2);

        assertTrue(compacted);
        assertEquals(List.of(selectedFile), compactor.getCapturedFiles());
        assertFalse(compactor.getCapturedFiles().contains(untouchedFile));
    }

    private static DataFileReader mockReader(final int index, final int level) {
        final DataFileMetadata metadata = mock(DataFileMetadata.class);
        when(metadata.getIndex()).thenReturn(index);
        when(metadata.getCompactionLevel()).thenReturn(level);

        final DataFileReader reader = mock(DataFileReader.class);
        when(reader.getIndex()).thenReturn(index);
        when(reader.getMetadata()).thenReturn(metadata);
        when(reader.getSize()).thenReturn(0L);
        return reader;
    }

    private static class TestDataFileCompactor extends DataFileCompactor {

        private List<DataFileReader> capturedFiles = List.of();
        private int capturedTargetLevel = -1;

        private TestDataFileCompactor(
                final MerkleDbConfig config,
                final DataFileCollection dataFileCollection,
                final CASableLongIndex index) {
            super(config, "test-store", dataFileCollection, index, null, null, null, null);
        }

        @Override
        List<Path> compactFiles(
                final CASableLongIndex index,
                final List<? extends DataFileReader> filesToCompact,
                final int targetCompactionLevel) {
            capturedFiles = new ArrayList<>(filesToCompact);
            capturedTargetLevel = targetCompactionLevel;
            return List.of();
        }

        private List<DataFileReader> getCapturedFiles() {
            return capturedFiles;
        }

        private int getCapturedTargetLevel() {
            return capturedTargetLevel;
        }
    }
}
