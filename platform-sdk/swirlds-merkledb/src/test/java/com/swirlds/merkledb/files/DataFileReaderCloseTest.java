// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCompactor.INITIAL_COMPACTION_LEVEL;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListOffHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DataFileReaderCloseTest {

    private static DataFileCollection collection;

    @BeforeAll
    static void setup() throws IOException {
        final Path dir = LegacyTemporaryFileBuilder.buildTemporaryFile("readerIsOpenTest", CONFIGURATION);
        final MerkleDbConfig dbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        collection = new DataFileCollection(dbConfig, dir, "store", null);
    }

    @AfterAll
    static void teardown() throws IOException {
        collection.close();
    }

    @Test
    void readerIsOpenTest() throws Exception {
        final int COUNT = 100;
        collection.updateValidKeyRange(0, COUNT - 1);
        collection.startWriting();
        final LongList index = new LongListOffHeap(COUNT / 10, COUNT, COUNT / 10);
        index.updateValidRange(0, COUNT - 1);
        for (int i = 0; i < COUNT; i++) {
            final int fi = i;
            index.put(
                    i,
                    collection.storeDataItem(
                            o -> {
                                o.writeLong(fi);
                                o.writeLong(fi + 1);
                            },
                            2 * Long.BYTES));
        }
        //noinspection resource
        collection.endWriting();
        final AtomicBoolean readingThreadStarted = new AtomicBoolean(false);
        final AtomicReference<IOException> exceptionOccurred = new AtomicReference<>();
        final Thread readingThread = new Thread(() -> {
            final Random rand = new Random();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    final int i = rand.nextInt(COUNT);
                    final long dataLocation = index.get(i);
                    final BufferedData itemBytes = collection.readDataItem(dataLocation);
                    Assertions.assertEquals(i, itemBytes.readLong());
                    Assertions.assertEquals(i + 1, itemBytes.readLong());
                    readingThreadStarted.set(true);
                }
            } catch (final ClosedByInterruptException e) {
                // This is expected
            } catch (final IOException e) {
                exceptionOccurred.set(e);
            }
        });
        readingThread.start();
        while (!readingThreadStarted.get()) {
            //noinspection BusyWait
            Thread.sleep(1);
        }
        readingThread.interrupt();
        readingThread.join(4000);
        if (exceptionOccurred.get() != null) {
            exceptionOccurred.get().printStackTrace();
            Assertions.assertNull(exceptionOccurred.get(), "No IOException is expected");
        }
        for (int i = 0; i < COUNT; i++) {
            final BufferedData itemBytes = collection.readDataItemUsingIndex(index, i);
            Assertions.assertEquals(i, itemBytes.readLong());
            Assertions.assertEquals(i + 1, itemBytes.readLong());
        }
    }

    @Test
    void readWhileFinishWritingTest() throws IOException {
        final Path tmpDir =
                LegacyTemporaryFileBuilder.buildTemporaryDirectory("readWhileFinishWritingTest", CONFIGURATION);
        final MerkleDbConfig dbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final int COUNT = 100;
        for (int i = 0; i < COUNT; i++) {
            Path filePath = null;
            final int fi = i;
            try {
                final DataFileWriter writer =
                        new DataFileWriter("test", tmpDir, i, Instant.now(), INITIAL_COMPACTION_LEVEL);
                filePath = writer.getPath();
                final DataFileMetadata metadata = writer.getMetadata();
                final LongList index = new LongListOffHeap(COUNT / 10, COUNT, COUNT / 10);
                index.updateValidRange(0, i);
                index.put(
                        0,
                        writer.storeDataItem(
                                o -> {
                                    o.writeLong(fi);
                                    o.writeLong(fi * 2 + 1);
                                },
                                2 * Long.BYTES));
                final DataFileReader reader = new DataFileReader(dbConfig, filePath, metadata);
                // Check the item in parallel to finish writing
                IntStream.of(0, 1).parallel().forEach(t -> {
                    try {
                        if (t == 1) {
                            writer.close();
                        } else {
                            final BufferedData itemBytes = reader.readDataItem(index.get(0));
                            Assertions.assertEquals(fi, itemBytes.readLong());
                            Assertions.assertEquals(fi * 2 + 1, itemBytes.readLong());
                        }
                    } catch (final IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } finally {
                if (filePath != null) {
                    Files.delete(filePath);
                }
            }
        }
    }
}
