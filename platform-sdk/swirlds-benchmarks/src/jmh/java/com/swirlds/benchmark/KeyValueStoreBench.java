// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.benchmark.Utils.RUN_DELIMITER;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.collections.LongListOffHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.MemoryIndexDiskKeyValueStore;
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
public class KeyValueStoreBench extends BaseBench {

    private static final Logger logger = LogManager.getLogger(KeyValueStoreBench.class);

    @Override
    String benchmarkName() {
        return "KeyValueStoreBench";
    }

    @Benchmark
    public void merge() throws Exception {
        String storeName = "mergeBench";
        setTestDir(storeName);

        logger.info(RUN_DELIMITER);

        final BenchmarkRecord[] map = new BenchmarkRecord[verify ? maxKey : 0];
        LongListOffHeap keyToDiskLocationIndex = new LongListOffHeap(1024 * 1024, maxKey, 256 * 1024);
        final MerkleDbConfig dbConfig = getConfig(MerkleDbConfig.class);
        final var store = new MemoryIndexDiskKeyValueStore(
                dbConfig, getTestDir(), storeName, null, (dataLocation, dataValue) -> {}, keyToDiskLocationIndex);
        final DataFileCompactor compactor = new DataFileCompactor(
                dbConfig, storeName, store.getFileCollection(), keyToDiskLocationIndex, null, null, null, null);

        // Write files
        long start = System.currentTimeMillis();
        for (int i = 0; i < numFiles; i++) {
            store.updateValidKeyRange(0, maxKey);
            store.startWriting();
            resetKeys();
            for (int j = 0; j < numRecords; ++j) {
                long id = nextAscKey();
                BenchmarkRecord value = new BenchmarkRecord(id, nextValue());
                store.put(id, value::serialize, value.getSizeInBytes());
                if (verify) map[(int) id] = value;
            }
            store.endWriting();
        }
        logger.info("Created {} files in {} ms", numFiles, System.currentTimeMillis() - start);

        // Merge files
        start = System.currentTimeMillis();
        compactor.compact();
        logger.info("Compacted files in {} ms", System.currentTimeMillis() - start);

        // Verify merged content
        if (verify) {
            start = System.currentTimeMillis();
            for (int key = 0; key < map.length; ++key) {
                BufferedData dataItemBytes = store.get(key);
                if (dataItemBytes == null) {
                    if (map[key] != null) {
                        throw new RuntimeException("Missing value");
                    }
                } else {
                    BenchmarkRecord dataItem = new BenchmarkRecord();
                    dataItem.deserialize(dataItemBytes);
                    if (!dataItem.equals(map[key])) {
                        throw new RuntimeException("Bad value");
                    }
                }
            }
            logger.info("Verified key-value store in {} ms", System.currentTimeMillis() - start);
        }

        store.close();
    }
}
