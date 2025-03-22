// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.stream;

import static com.swirlds.common.test.fixtures.stream.TestStreamType.TEST_STREAM;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.common.stream.HashCalculatorForStream;
import com.swirlds.common.stream.QueueThreadObjectStream;
import com.swirlds.common.stream.QueueThreadObjectStreamConfiguration;
import com.swirlds.common.stream.RunningHashCalculatorForStream;
import com.swirlds.common.stream.Signer;
import com.swirlds.common.stream.internal.TimestampStreamFileWriter;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * For testing object stream;
 * takes objects from {@link ObjectForTestStreamGenerator},
 * sends objects to LinkedObjectStream objects for calculating RunningHash and serializing to disk
 */
public class StreamObjectWorker {
    /** receives objects from runningHashCalculator, then passes to writeConsumer */
    private final QueueThreadObjectStream<ObjectForTestStream> writeQueueThread;

    private Iterator<ObjectForTestStream> iterator;
    /** receives objects from a generator, then passes to hashCalculator */
    private QueueThreadObjectStream<ObjectForTestStream> hashQueueThread;
    /**
     * number of objects needs to be processed
     */
    private int remainNum;
    /**
     * objects that has been added, is used for unit test
     */
    private Deque<SelfSerializable> addedObjects;

    public StreamObjectWorker(
            int totalNum,
            int intervalMs,
            String dirPath,
            int logPeriodMs,
            Hash initialHash,
            boolean startWriteAtCompleteWindow,
            Instant firstTimestamp,
            Signer signer)
            throws NoSuchAlgorithmException {

        // writes objects to files
        TimestampStreamFileWriter<ObjectForTestStream> fileWriter =
                new TimestampStreamFileWriter<>(dirPath, logPeriodMs, signer, startWriteAtCompleteWindow, TEST_STREAM);

        writeQueueThread = new QueueThreadObjectStreamConfiguration<ObjectForTestStream>(getStaticThreadManager())
                .setForwardTo(fileWriter)
                .build();
        writeQueueThread.start();

        initialize(totalNum, intervalMs, initialHash, firstTimestamp);
    }

    public StreamObjectWorker(
            int totalNum, int intervalMs, Hash initialHash, Instant firstTimestamp, SerializableDataOutputStream stream)
            throws IOException {
        // writes objects to a stream
        WriteToStreamConsumer streamWriter = new WriteToStreamConsumer(stream, initialHash);

        writeQueueThread = new QueueThreadObjectStreamConfiguration<ObjectForTestStream>(getStaticThreadManager())
                .setForwardTo(streamWriter)
                .build();
        writeQueueThread.start();

        initialize(totalNum, intervalMs, initialHash, firstTimestamp);
    }

    private void initialize(int totalNum, int intervalMs, Hash initialHash, Instant firstTimestamp) {
        this.remainNum = totalNum;
        this.iterator = new ObjectForTestStreamGenerator(totalNum, intervalMs, firstTimestamp).getIterator();
        // receives objects from hashCalculator, calculates and set runningHash for this object
        final RunningHashCalculatorForStream<ObjectForTestStream> runningHashCalculator =
                new RunningHashCalculatorForStream<>(writeQueueThread);

        // receives objects from hashQueueThread, calculates it's Hash, then passes to
        // runningHashCalculator
        final HashCalculatorForStream<ObjectForTestStream> hashCalculator =
                new HashCalculatorForStream<>(runningHashCalculator);

        hashQueueThread = new QueueThreadObjectStreamConfiguration<ObjectForTestStream>(getStaticThreadManager())
                .setForwardTo(hashCalculator)
                .build();
        hashQueueThread.setRunningHash(initialHash);
        hashQueueThread.start();

        addedObjects = new LinkedList<>();
    }

    public void work() {
        while (remainNum > 0 && iterator.hasNext()) {
            ObjectForTestStream object = iterator.next();

            // send this object to hashQueueThread
            hashQueueThread.getQueue().add(object);
            addedObjects.add(object);

            // if this is the last object,
            // should tell consumer to close current file after writing this object
            if (!iterator.hasNext()) {
                hashQueueThread.close();
            }

            remainNum--;
        }
    }

    public Deque<SelfSerializable> getAddedObjects() {
        return addedObjects;
    }
}
