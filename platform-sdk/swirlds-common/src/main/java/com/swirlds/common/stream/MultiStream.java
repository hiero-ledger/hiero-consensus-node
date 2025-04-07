// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

import static com.swirlds.logging.legacy.LogMarker.OBJECT_STREAM;

import com.swirlds.common.stream.internal.LinkedObjectStream;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.RunningHashable;

/**
 * A MultiStream instance might have multiple nextStreams.
 * It accepts a SerializableRunningHashable object each time, and sends it to each of its nextStreams
 *
 * @param <T>
 * 		type of the objects
 */
public class MultiStream<T extends RunningHashable> implements LinkedObjectStream<T> {
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(MultiStream.class);

    /**
     * message of the exception thrown when setting a nextStream to be null
     */
    public static final String NEXT_STREAM_NULL = "MultiStream should not have null nextStream";

    /**
     * nextStreams should have at least this many elements
     */
    private static final int NEXT_STREAMS_MIN_SIZE = 1;

    /**
     * message of the exception thrown when nextStreams has less than two elements
     */
    public static final String NOT_ENOUGH_NEXT_STREAMS =
            String.format("MultiStream should have at least %d " + "nextStreams", NEXT_STREAMS_MIN_SIZE);

    /**
     * a list of LinkedObjectStreams which receives objects from this multiStream
     */
    private List<LinkedObjectStream<T>> nextStreams;

    public MultiStream(List<LinkedObjectStream<T>> nextStreams) {
        if (nextStreams == null || nextStreams.size() < NEXT_STREAMS_MIN_SIZE) {
            throw new IllegalArgumentException(NOT_ENOUGH_NEXT_STREAMS);
        }

        for (LinkedObjectStream<T> nextStream : nextStreams) {
            if (nextStream == null) {
                throw new IllegalArgumentException(NEXT_STREAM_NULL);
            }
        }
        this.nextStreams = nextStreams;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRunningHash(final Hash hash) {
        for (LinkedObjectStream<T> nextStream : nextStreams) {
            nextStream.setRunningHash(hash);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addObject(T t) {
        for (LinkedObjectStream<T> nextStream : nextStreams) {
            nextStream.addObject(t);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        for (LinkedObjectStream<T> nextStream : nextStreams) {
            nextStream.clear();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        for (LinkedObjectStream<T> nextStream : nextStreams) {
            nextStream.close();
        }
        logger.info(OBJECT_STREAM.getMarker(), "MultiStream is closed");
    }
}
