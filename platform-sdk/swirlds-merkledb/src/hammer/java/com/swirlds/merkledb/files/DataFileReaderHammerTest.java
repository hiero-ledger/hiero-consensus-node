// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCompactor.INITIAL_COMPACTION_LEVEL;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@Disabled("This test needs to be investigated")
public class DataFileReaderHammerTest {

    @Test
    @DisplayName("Test DataFileReader with interrupts")
    void interruptedReadsHammerTest() throws Exception {
        final int fileSize = 10_000_000;
        final int itemCount = 1_000;
        final int itemSize = fileSize / itemCount;
        final int readerThreads = 32;
        final int readIterations = 10_000;

        final Path tempFile =
                LegacyTemporaryFileBuilder.buildTemporaryFile("interruptedReadsHammerTest", CONFIGURATION);
        final ByteBuffer writeBuf = ByteBuffer.allocate(itemSize);
        for (int i = 0; i < itemSize; i++) {
            writeBuf.put((byte) (i % 100));
        }
        final FileChannel writeChannel =
                FileChannel.open(tempFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        for (int i = 0; i < itemCount; i++) {
            writeBuf.clear();
            writeChannel.write(writeBuf);
        }
        writeChannel.close();

        final ExecutorService exec = Executors.newFixedThreadPool(readerThreads);
        final Random rand = new Random();
        final MerkleDbConfig dbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final DataFileMetadata metadata = new DataFileMetadata(0, Instant.now(), INITIAL_COMPACTION_LEVEL);
        final DataFileReader dataReader = new DataFileReader(dbConfig, tempFile, metadata);
        final AtomicInteger activeReaders = new AtomicInteger(readerThreads);
        final AtomicReferenceArray<Thread> threads = new AtomicReferenceArray<>(readerThreads);
        final Future<?>[] jobs = new Future[readerThreads];
        for (int i = 0; i < readerThreads; i++) {
            final int threadNo = i;
            final Future<?> result = exec.submit(() -> {
                threads.set(threadNo, Thread.currentThread());
                try {
                    for (int j = 0; j < readIterations; j++) {
                        try {
                            final int start = rand.nextInt(itemCount) * itemSize;
                            final BufferedData data = dataReader.readDataItem(start);
                            Assertions.assertNotNull(data);
                            for (int k = 0; k < itemSize; k++) {
                                Assertions.assertEquals(k % 100, data.getByte(k));
                            }
                        } catch (final ClosedByInterruptException e) {
                            Thread.interrupted(); // clear "interrupted" status and keep going
                        } catch (final IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                } finally {
                    activeReaders.decrementAndGet();
                }
            });
            jobs[i] = result;
        }
        // Add chaos: interrupt random threads, but not same thread twice in a row
        int lastInterruptedThread = -1;
        while (activeReaders.get() > 0) {
            try {
                // Don't interrupt too often
                Thread.sleep(rand.nextInt(100) + 300);
            } catch (final InterruptedException e) {
                // ignore
            }
            final int threadToInterrupt = rand.nextInt(readerThreads);
            if (threadToInterrupt != lastInterruptedThread) {
                final Thread thread = threads.get(threadToInterrupt);
                if (thread != null) {
                    thread.interrupt();
                }
                lastInterruptedThread = threadToInterrupt;
            }
        }
        // Check for exceptions
        for (int i = 0; i < readerThreads; i++) {
            Future<?> result = jobs[i];
            result.get();
        }
    }
}
