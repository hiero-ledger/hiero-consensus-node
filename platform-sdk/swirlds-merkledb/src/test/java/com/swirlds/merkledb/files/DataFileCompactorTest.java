// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCompactor.compactionPlan;
import static java.util.Collections.emptyList;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.nextInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class DataFileCompactorTest {

    @Mock
    DataFileReader initialLevel1;

    @Mock
    DataFileReader initialLevel2;

    @Mock
    DataFileReader initialLevel3;

    @Mock
    DataFileReader firstLevel1;

    @Mock
    DataFileReader firstLevel2;

    @Mock
    DataFileReader secondLevel1;

    @Mock
    DataFileReader secondLevel2;

    @BeforeEach
    void setUp() {
        openMocks(this);
        initReaderLevel(initialLevel1, 0);
        initReaderLevel(initialLevel2, 0);
        initReaderLevel(initialLevel3, 0);
        initReaderLevel(firstLevel1, 1);
        initReaderLevel(firstLevel2, 1);
        initReaderLevel(secondLevel1, 2);
        initReaderLevel(secondLevel2, 2);
    }

    private void initReaderLevel(DataFileReader reader, int level) {
        DataFileMetadata metadata = mock(DataFileMetadata.class);
        when(reader.getMetadata()).thenReturn(metadata);
        when(metadata.getCompactionLevel()).thenReturn(level);
    }

    @Test
    void testEmptyCompactionPlan() {
        assertEquals(0, compactionPlan(emptyList(), nextInt(), nextInt()).size());
    }

    @Test
    void testCompactionPlanInitialOnly_notEnoughReaders() {
        assertEquals(
                0,
                compactionPlan(Collections.singletonList(initialLevel1), 2, 5).size());
    }

    @Test
    void testCompactionPlanInitialOnly() {
        List<? extends DataFileReader> result =
                compactionPlan(Arrays.asList(initialLevel1, initialLevel2, initialLevel3), 2, 5);
        assertEquals(3, result.size());
        assertEquals(initialLevel1, result.get(0));
        assertEquals(initialLevel2, result.get(1));
        assertEquals(initialLevel3, result.get(2));
    }

    @Test
    void testCompactionPlanMultiLevel_notEnoughLevel1Readers() {
        List<? extends DataFileReader> result =
                compactionPlan(Arrays.asList(initialLevel1, initialLevel2, initialLevel3, firstLevel1), 3, 5);
        assertEquals(3, result.size());
        assertEquals(initialLevel1, result.get(0));
        assertEquals(initialLevel2, result.get(1));
        assertEquals(initialLevel3, result.get(2));
    }

    @Test
    void testCompactionPlanMultiLevel() {
        List<? extends DataFileReader> result = compactionPlan(
                Arrays.asList(initialLevel1, initialLevel2, initialLevel3, firstLevel1, firstLevel2), 3, 5);
        assertEquals(5, result.size());
        assertEquals(initialLevel1, result.get(0));
        assertEquals(initialLevel2, result.get(1));
        assertEquals(initialLevel3, result.get(2));
        assertEquals(firstLevel1, result.get(3));
        assertEquals(firstLevel2, result.get(4));
    }

    @Test
    void testCompactionPlanMultiLevel_threeLevels() {
        List<? extends DataFileReader> result = compactionPlan(
                Arrays.asList(
                        initialLevel1,
                        initialLevel2,
                        initialLevel3,
                        firstLevel1,
                        firstLevel2,
                        secondLevel1,
                        secondLevel2),
                3,
                5);
        assertEquals(7, result.size());
        assertEquals(initialLevel1, result.get(0));
        assertEquals(initialLevel2, result.get(1));
        assertEquals(initialLevel3, result.get(2));
        assertEquals(firstLevel1, result.get(3));
        assertEquals(firstLevel2, result.get(4));
        assertEquals(secondLevel1, result.get(5));
        assertEquals(secondLevel2, result.get(6));
    }

    @Test
    void testCompactionPlanMultiLevel_notEnoughFilesOnMidLevel() {
        List<? extends DataFileReader> result = compactionPlan(
                Arrays.asList(initialLevel1, initialLevel2, initialLevel3, firstLevel1, secondLevel1, secondLevel2),
                3,
                5);
        assertEquals(3, result.size());
        assertEquals(initialLevel1, result.get(0));
        assertEquals(initialLevel2, result.get(1));
        assertEquals(initialLevel3, result.get(2));
    }

    @Test
    void testCompactionPlanMultiLevel_noInitialLevelFiles() {
        List<? extends DataFileReader> result =
                compactionPlan(Arrays.asList(firstLevel1, secondLevel1, secondLevel2), 3, 5);
        assertEquals(0, result.size());
    }
}
