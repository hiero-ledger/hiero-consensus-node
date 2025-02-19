/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.benchmark;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListOffHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.DataFileReader;
import java.io.IOException;
import java.util.List;
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

    String benchmarkName() {
        return "DataFileCollectionBench";
    }

    @Benchmark
    public void compaction() throws Exception {
        String storeName = "compactionBench";
        beforeTest(storeName);

        final LongListOffHeap index = new LongListOffHeap();
        final BenchmarkRecord[] map = new BenchmarkRecord[verify ? maxKey : 0];
        final MerkleDbConfig dbConfig = getConfig(MerkleDbConfig.class);
        final BenchmarkRecordSerializer serializer = new BenchmarkRecordSerializer();
        final var store =
                new DataFileCollection(dbConfig, getTestDir(), storeName, null, (dataLocation, dataValue) -> {}) {
                    BenchmarkRecord read(long dataLocation) throws IOException {
                        final BufferedData recordData = readDataItem(dataLocation);
                        return recordData != null ? serializer.deserialize(recordData) : null;
                    }
                };
        final var compactor = new DataFileCompactor(dbConfig, storeName, store, index, null, null, null, null);
        System.out.println();

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
            store.endWriting(0, maxKey).setFileCompleted();
        }
        System.out.println("Created " + numFiles + " files in " + (System.currentTimeMillis() - start) + "ms");

        // Merge files
        start = System.currentTimeMillis();
        final List<DataFileReader> filesToMerge = store.getAllCompletedFiles();
        compactor.compact();
        System.out.println(
                "Merged " + filesToMerge.size() + " files in " + (System.currentTimeMillis() - start) + "ms");

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
            System.out.println(
                    "Verified " + store.getNumOfFiles() + " file(s) in " + (System.currentTimeMillis() - start) + "ms");
        }

        afterTest(() -> {
            store.close();
            index.close();
        });
    }
}
