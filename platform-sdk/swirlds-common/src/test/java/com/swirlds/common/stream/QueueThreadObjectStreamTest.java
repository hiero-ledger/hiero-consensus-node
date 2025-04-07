// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import com.swirlds.common.test.fixtures.crypto.CryptoRandomUtils;
import com.swirlds.common.test.fixtures.stream.ObjectForTestStream;
import com.swirlds.common.test.fixtures.stream.ObjectForTestStreamGenerator;
import com.swirlds.common.test.fixtures.stream.WriteToStreamConsumer;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.SelfSerializable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueThreadObjectStreamTest {
    private static final Hash initialHash = new Hash(new byte[DigestType.SHA_384.digestLength()]);
    private static Cryptography cryptography;
    private static WriteToStreamConsumer consumer;
    private static QueueThreadObjectStream<ObjectForTestStream> queueThread;
    private static RunningHashCalculatorForStream<ObjectForTestStream> runningHashCalculator;
    private static HashCalculatorForStream<ObjectForTestStream> hashCalculator;
    private static Iterator<ObjectForTestStream> iterator;
    private final int intervalMs = 50;
    private final int totalNum = 50;

    @BeforeAll
    static void init() {
        cryptography = mock(Cryptography.class);
        when(cryptography.digestSync(any(SelfSerializable.class))).thenReturn(CryptoRandomUtils.randomHash());
    }

    @BeforeEach
    void initLinkedObjectStreams() throws IOException {
        consumer = new WriteToStreamConsumer(
                new SerializableDataOutputStreamImpl(new BufferedOutputStream(new ByteArrayOutputStream())),
                initialHash);

        queueThread = new QueueThreadObjectStreamConfiguration<ObjectForTestStream>(getStaticThreadManager())
                .setForwardTo(consumer)
                .build();
        runningHashCalculator = new RunningHashCalculatorForStream<>(queueThread);
        hashCalculator = new HashCalculatorForStream<>(runningHashCalculator);
        hashCalculator.setRunningHash(initialHash);

        iterator = new ObjectForTestStreamGenerator(totalNum, intervalMs, Instant.now()).getIterator();

        assertTrue(queueThread.getQueue().isEmpty(), "the queue should be empty after initialized");
    }

    @Test
    void closeTest() {
        final int targetConsumedNum = 20;

        queueThread.start();

        int consumedNum = 0;
        while (consumedNum < targetConsumedNum) {
            consumedNum++;
            hashCalculator.addObject(iterator.next());
        }
        queueThread.close();

        ObjectForTestStream nextObject = iterator.next();

        assertTrue(consumer.isClosed, "consumer should also be closed");

        assertEquals(
                targetConsumedNum,
                consumer.consumedCount,
                "the number of objects the consumer have consumed should be the same as targetConsumedNum");
    }

    @Test
    void clearTest() {
        final int targetNumBeforeClear = 20;

        queueThread.start();

        int addedNum = 0;
        while (addedNum < targetNumBeforeClear) {
            addedNum++;
            hashCalculator.addObject(iterator.next());
        }
        // clear queueThread
        queueThread.clear();

        // continue consuming after clear
        while (iterator.hasNext()) {
            addedNum++;
            hashCalculator.addObject(iterator.next());
        }

        // close queueThread
        queueThread.stop();
        assertTrue(
                consumer.consumedCount <= addedNum,
                String.format(
                        "the number of objects the consumer have consumed should be less than or equal "
                                + "to totalNum %d < %d",
                        consumer.consumedCount, addedNum));
    }
}
