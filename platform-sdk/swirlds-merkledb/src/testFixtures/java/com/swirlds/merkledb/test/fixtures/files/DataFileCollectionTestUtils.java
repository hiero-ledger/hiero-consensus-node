// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures.files;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.files.DataFileCollection;
import java.util.Arrays;
import org.hiero.base.constructable.ConstructableIgnored;

@ConstructableIgnored
public class DataFileCollectionTestUtils {

    /**
     * For tests, we want to have all different data sizes, so we use this function to choose how
     * many times to repeat the data value long
     */
    private static int getRepeatCountForKey(final long key) {
        return (int) (key % 20L);
    }

    /** Create an example variable sized data item with lengths of data from 1 to 20. */
    public static long[] getVariableSizeDataForI(final int i, final int valueAddition) {
        final int repeatCount = getRepeatCountForKey(i);
        final long[] dataValue = new long[1 + repeatCount];
        dataValue[0] = i;
        for (int j = 1; j < dataValue.length; j++) {
            dataValue[j] = i + valueAddition;
        }
        return dataValue;
    }

    public static void checkData(
            final DataFileCollection fileCollection,
            final LongListHeap storedOffsets,
            final FilesTestType testType,
            final int fromIndex,
            final int toIndexExclusive,
            final int valueAddition) {
        // now read back all the data and check all data
        for (int i = fromIndex; i < toIndexExclusive; i++) {
            // test read with index
            final long fi = i;
            final BufferedData dataItem = assertDoesNotThrow(
                    () -> fileCollection.readDataItemUsingIndex(storedOffsets, fi), "Read should not a exception.");
            checkDataItem(testType, valueAddition, dataItem, i);
        }
    }

    static void checkDataItem(
            FilesTestType testType, final int valueAddition, final BufferedData dataItem, final int expectedKey) {
        assertNotNull(dataItem, "dataItem should not be null");
        switch (testType) {
            default:
            case fixed:
                assertEquals(2 * Long.BYTES, dataItem.remaining(), "unexpected length"); // size
                assertEquals(expectedKey, dataItem.readLong(), "unexpected key"); // key
                assertEquals(expectedKey + valueAddition, dataItem.readLong(), "unexpected value"); // value
                break;
            case variable:
                final long[] dataItemLongs = new long[Math.toIntExact(dataItem.remaining() / Long.BYTES)];
                for (int i = 0; i < dataItemLongs.length; i++) {
                    dataItemLongs[i] = dataItem.readLong();
                }
                assertEquals(
                        Arrays.toString(getVariableSizeDataForI(expectedKey, valueAddition)),
                        Arrays.toString(dataItemLongs),
                        "unexpected dataItem value");
                break;
        }
    }
}
