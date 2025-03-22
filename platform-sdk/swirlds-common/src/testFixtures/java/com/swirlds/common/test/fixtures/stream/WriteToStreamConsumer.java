// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.stream;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.stream.internal.LinkedObjectStream;
import com.swirlds.common.stream.internal.SingleStreamIterator;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.RunningHash;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * Is used for testing {@link SingleStreamIterator}
 *
 * writes Hash and objects to a stream;
 */
public class WriteToStreamConsumer implements LinkedObjectStream<ObjectForTestStream> {
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(WriteToStreamConsumer.class);

    private static final Marker LOGM_OBJECT_STREAM = MarkerManager.getMarker("OBJECT_STREAM");
    private static final Marker LOGM_EXCEPTION = MarkerManager.getMarker("EXCEPTION");
    /**
     * the stream to which we write RunningHash and serialize objects
     */
    private SerializableDataOutputStream outputStream;

    /**
     * current runningHash
     */
    private RunningHash runningHash;

    public boolean isClosed = false;

    public int consumedCount = 0;

    public WriteToStreamConsumer(SerializableDataOutputStream stream, Hash startRunningHash) throws IOException {
        this.outputStream = stream;
        try {
            this.outputStream.writeSerializable(startRunningHash, true);
        } catch (IOException ex) {
            throw new IOException(
                    String.format(
                            "Failed to write startRunningHash: %s. IOException: %s", startRunningHash, ex.getMessage()),
                    ex.getCause());
        }

        logger.info(LOGM_OBJECT_STREAM, "Wrote startRunningHash: {}", startRunningHash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addObject(ObjectForTestStream object) {
        try {
            outputStream.writeSerializable(object, true);
            logger.info(LOGM_OBJECT_STREAM, "write object: {}", object);
            // update runningHash
            this.runningHash = object.getRunningHash();
            consumedCount++;
        } catch (IOException ex) {
            logger.error(EXCEPTION.getMarker(), "Failed to add object", ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        try {
            outputStream.writeSerializable(this.runningHash.getFutureHash().getAndRethrow(), true);
            logger.info(LOGM_OBJECT_STREAM, "Wrote endRunningHash: {}", this.runningHash);
            outputStream.close();
            isClosed = true;
        } catch (IOException ex) {
            logger.error(EXCEPTION.getMarker(), "Failed to close output stream", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        try {
            outputStream.close();
        } catch (IOException ex) {
            logger.error(EXCEPTION.getMarker(), "Failed to close output stream", ex);
        }
    }

    @Override
    public void setRunningHash(final Hash hash) {
        this.runningHash = new RunningHash(hash);
    }
}
