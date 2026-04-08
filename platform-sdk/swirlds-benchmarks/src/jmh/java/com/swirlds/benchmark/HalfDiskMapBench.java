// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.benchmark.BenchmarkKeyUtils.longToKey;
import static com.swirlds.benchmark.Utils.RUN_DELIMITER;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Thread)
@Warmup(iterations = 1)
@Measurement(iterations = 5)
public class HalfDiskMapBench extends BaseBench {

    private static final Logger logger = LogManager.getLogger(HalfDiskMapBench.class);

    private static final long INVALID_PATH = -1L;

    @Override
    String benchmarkName() {
        return "HalfDiskMapBench";
    }

    @Benchmark
    public void merge() throws Exception {
        String storeName = "mergeBench";
        setTestDir(storeName);

        logger.info(RUN_DELIMITER);

        final long[] map = new long[verify ? maxKey : 0];
        Arrays.fill(map, INVALID_PATH);

        final var store = new HalfDiskHashMap(configuration, maxKey, getTestDir(), storeName, null, false);
        final var dataFileCompactor = new DataFileCompactor(
                storeName, store.getFileCollection(), store.getBucketIndexToBucketLocation(), null, null, null, null);

        // Write files
        long start = System.currentTimeMillis();
        for (int i = 0; i < numFiles; i++) {
            store.startWriting();
            resetKeys();
            for (int j = 0; j < numRecords; ++j) {
                long id = nextAscKey();
                long value = nextValue();
                final Bytes key = longToKey(id);
                store.put(key, value);
                if (verify) map[(int) id] = value;
            }
            store.endWriting();
        }

        logger.info("Created {} files in {} ms", numFiles, System.currentTimeMillis() - start);

        // Merge files
        start = System.currentTimeMillis();
        dataFileCompactor.compactSingleLevel(
                dataFileCompactor.getDataFileCollection().getAllCompletedFiles(), 1);
        logger.info("Compacted files in {} ms", System.currentTimeMillis() - start);

        // Verify merged content
        if (verify) {
            start = System.currentTimeMillis();
            for (int id = 0; id < map.length; ++id) {
                final Bytes key = longToKey(id);
                long value = store.get(key, INVALID_PATH);
                if (value != map[id]) {
                    throw new RuntimeException("Bad value");
                }
            }
            logger.info("Verified HalfDiskHashMap in {} ms", System.currentTimeMillis() - start);
        }

        store.close();
    }
}
