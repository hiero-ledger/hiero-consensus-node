// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.benchmark.Utils.RUN_DELIMITER;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListSegment;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.DataFileReader;
import java.io.IOException;
import java.util.List;
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
public class DataFileCollectionBench extends BaseBench {

    private static final Logger logger = LogManager.getLogger(DataFileCollectionBench.class);

    @Override
    String benchmarkName() {
        return "DataFileCollectionBench";
    }

    @Benchmark
    public void compaction() throws Exception {
        String storeName = "compactionBench";
        setTestDir(storeName);

        logger.info(RUN_DELIMITER);

        final LongListSegment index = new LongListSegment(1024 * 1024, maxKey, 256 * 1024);
        index.updateValidRange(0, maxKey - 1);
        final BenchmarkRecord[] map = new BenchmarkRecord[verify ? maxKey : 0];
        final MerkleDbConfig dbConfig = getConfig(MerkleDbConfig.class);
        final var store =
                new DataFileCollection(dbConfig, getTestDir(), storeName, null, (dataLocation, dataValue) -> {}) {
                    BenchmarkRecord read(long dataLocation) throws IOException {
                        final BufferedData recordData = readDataItem(dataLocation);
                        if (recordData == null) {
                            return null;
                        } else {
                            BenchmarkRecord benchmarkRecord = new BenchmarkRecord();
                            benchmarkRecord.deserialize(recordData);
                            return benchmarkRecord;
                        }
                    }
                };

        final var compactor = new DataFileCompactor(storeName, store, index, null, null, null, null);

        // Write files
        long start = System.currentTimeMillis();
        for (int i = 0; i < numFiles; i++) {
            store.startWriting();
            resetKeys();
            for (int j = 0; j < numRecords; ++j) {
                long id = nextAscKey();
                BenchmarkRecord record = new BenchmarkRecord(id, nextValue());
                index.put(id, store.storeDataItem(record::serialize, record.getSizeInBytes()));
                if (verify) map[(int) id] = record;
            }
            store.updateValidKeyRange(0, maxKey - 1);
            store.endWriting();
        }
        logger.info("Created {} files in {} ms", numFiles, System.currentTimeMillis() - start);

        // Merge files
        start = System.currentTimeMillis();
        final List<DataFileReader> filesToMerge = store.getAllCompletedFiles();
        compactor.compactSingleLevel(compactor.getDataFileCollection().getAllCompletedFiles(), 1);
        logger.info("Merged {} files in {} ms", filesToMerge.size(), System.currentTimeMillis() - start);

        // Verify merged content
        if (verify) {
            start = System.currentTimeMillis();
            for (int key = 0; key < map.length; ++key) {
                BenchmarkRecord dataItem = store.read(index.get(key, LongList.IMPERMISSIBLE_VALUE));
                if (dataItem == null) {
                    if (map[key] != null) {
                        throw new RuntimeException("Missing value");
                    }
                } else if (!dataItem.equals(map[key])) {
                    throw new RuntimeException("Bad value");
                }
            }
            logger.info("Verified {} files in {} ms", store.getNumOfFiles(), System.currentTimeMillis() - start);
        }

        store.close();
        index.close();
    }
}
