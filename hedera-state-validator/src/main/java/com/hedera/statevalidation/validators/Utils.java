// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators;

import static com.swirlds.merkledb.files.DataFileCommon.dataLocationToString;
import static java.lang.Math.toIntExact;

import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import org.apache.logging.log4j.Logger;

public final class Utils {
    private Utils() {}

    @SuppressWarnings("StringConcatenationArgumentToLogCall")
    public static void printFileDataLocationError(
            Logger logger, String message, DataFileCollection dfc, long dataLocation) {
        final List<DataFileReader> dataFiles = dfc.getAllCompletedFiles();
        logger.error("Error! Details: " + message);
        logger.error("Data location: " + dataLocationToString(dataLocation));
        logger.error("Data file collection: ");
        dataFiles.forEach(a -> {
            logger.error("File: " + a.getPath());
            logger.error("Size: " + a.getSize());
            logger.error("Metadata: " + a.getMetadata());
        });
    }

    public static class LongCountArray {

        static final int LONGS_PER_CHUNK = 1 << 20;

        static final int BITS_PER_COUNT = 2;

        static final int COUNTS_PER_LONG = Long.SIZE / BITS_PER_COUNT;

        static final long COUNT_MASK = (1L << BITS_PER_COUNT) - 1;

        final long size;

        AtomicLongArray[] arrays;

        public LongCountArray(long size) {
            this.size = size;
            int maxChunkIndex = toIntExact((size - 1) / COUNTS_PER_LONG / LONGS_PER_CHUNK);
            arrays = new AtomicLongArray[maxChunkIndex + 1];
            for (int i = 0; i < arrays.length; ++i) {
                arrays[i] = new AtomicLongArray(LONGS_PER_CHUNK);
            }
        }

        public long size() {
            return size;
        }

        public long getAndIncrement(long idx) {
            if (idx < 0 || idx >= size) {
                throw new IndexOutOfBoundsException();
            }

            int chunkIdx = toIntExact(idx / COUNTS_PER_LONG / LONGS_PER_CHUNK);
            int longIdx = toIntExact((idx / COUNTS_PER_LONG) % LONGS_PER_CHUNK);
            int countOffset = toIntExact(idx % COUNTS_PER_LONG) * BITS_PER_COUNT;

            long val = arrays[chunkIdx].get(longIdx);

            while (true) {
                long count = (val >>> countOffset) & COUNT_MASK;
                if (count == COUNT_MASK) return count;
                long newVal = val + (1L << countOffset);
                long oldVal = arrays[chunkIdx].compareAndExchange(longIdx, val, newVal);
                if (oldVal == val) return count;
                val = oldVal;
            }
        }
    }
}
