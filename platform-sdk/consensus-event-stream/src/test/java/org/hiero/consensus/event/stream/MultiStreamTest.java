// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.stream;

import static org.hiero.consensus.event.stream.Constants.PAY_LOAD_SIZE_4;
import static org.hiero.consensus.event.stream.MultiStream.NEXT_STREAM_NULL;
import static org.hiero.consensus.event.stream.MultiStream.NOT_ENOUGH_NEXT_STREAMS;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.test.fixtures.CryptoRandomUtils;
import org.hiero.consensus.event.stream.internal.HashCalculatorForStream;
import org.hiero.consensus.event.stream.internal.QueueThreadObjectStream;
import org.hiero.consensus.event.stream.test.fixtures.ObjectForTestStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MultiStreamTest {
    private static LinkedObjectStream<ObjectForTestStream> hashCalculator;
    private static LinkedObjectStream<ObjectForTestStream> queueThread;
    private static LinkedObjectStream<ObjectForTestStream> multiStream;

    @BeforeAll
    static void init() {
        hashCalculator = mock(HashCalculatorForStream.class);
        queueThread = mock(QueueThreadObjectStream.class);
        List<LinkedObjectStream<ObjectForTestStream>> nextStreams = new ArrayList<>();
        nextStreams.add(hashCalculator);
        nextStreams.add(queueThread);
        multiStream = new MultiStream<>(nextStreams);
    }

    @Test
    void addTest() throws InterruptedException {
        ObjectForTestStream object = ObjectForTestStream.getRandomObjectForTestStream(PAY_LOAD_SIZE_4);
        multiStream.addObject(object);
        verify(hashCalculator).addObject(object);
        verify(queueThread).addObject(object);
    }

    @Test
    void clearTest() throws InterruptedException {
        multiStream.clear();
        verify(hashCalculator).clear();
        verify(queueThread).clear();
    }

    @Test
    void closeTest() {
        multiStream.close();
        verify(hashCalculator).close();
        verify(queueThread).close();
    }

    @Test
    void setHashTest() {
        Hash hash = CryptoRandomUtils.randomHash();
        multiStream.setRunningHash(hash);
        verify(hashCalculator).setRunningHash(hash);
        verify(queueThread).setRunningHash(hash);
    }

    @Test
    void addNullNextStreamsTest() {
        Exception exception = assertThrows(
                IllegalArgumentException.class,
                () -> new MultiStream<>(null),
                "should throw exception when setting null nextStreams to multiStream");
        assertTrue(exception.getMessage().contains(NOT_ENOUGH_NEXT_STREAMS), "nextStreams should not be null");
    }

    @Test
    void addZeroNextStreamTest() {
        List<LinkedObjectStream<ObjectForTestStream>> nextStreams = new ArrayList<>();
        Exception exception = assertThrows(
                IllegalArgumentException.class,
                () -> new MultiStream<>(nextStreams),
                "should throw exception when setting nextStreams with zero element to multiStream");
        assertTrue(
                exception.getMessage().contains(NOT_ENOUGH_NEXT_STREAMS), "nextStreams should have at least 1 element");
    }

    @Test
    void addNullNextStreamTest() {
        List<LinkedObjectStream<ObjectForTestStream>> nextStreams = new ArrayList<>(2);
        nextStreams.add(null);
        nextStreams.add(mock(LinkedObjectStream.class));
        Exception exception = assertThrows(
                IllegalArgumentException.class,
                () -> new MultiStream<>(nextStreams),
                "should throw exception when nextStreams of a multiStream contains null");
        assertTrue(exception.getMessage().contains(NEXT_STREAM_NULL), "nextStream should not be null");
    }
}
