// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream.internal;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.stream.StreamType;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.logging.legacy.payload.StreamParseErrorPayload;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;

/**
 * Parse an inputStream, return an Iterator from which we can get all SelfSerializable objects in the inputStream
 */
public class SingleStreamIterator<T extends SelfSerializable> implements Iterator<T> {
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(SingleStreamIterator.class);

    private static final Marker LOGM_OBJECT_STREAM = LogMarker.OBJECT_STREAM.getMarker();

    private static final Marker LOGM_EXCEPTION = LogMarker.EXCEPTION.getMarker();

    private SerializableDataInputStream stream;

    /**
     * stream is closed or stream is null
     */
    private boolean streamClosed;

    /**
     * initializes a SingleStreamIterator which consumes a stream from a stream file,
     * discards file header and objectStreamVersion,
     * the rest content only contains SelfSerializable objects: startRunningHash, stream objects, endRunningHash
     *
     * @param file
     * 		a stream file to be parsed
     * @param streamType
     * 		type of the stream file
     */
    public SingleStreamIterator(final File file, final StreamType streamType) {
        try {
            stream = new SerializableDataInputStream(new BufferedInputStream(new FileInputStream(file)));
            logger.info(LOGM_OBJECT_STREAM, "SingleStreamIterator :: reading file: {}", () -> file.getName());
            // read stream file header
            for (int i = 0; i < streamType.getFileHeader().length; i++) {
                stream.readInt();
            }
            // read OBJECT_STREAM_VERSION
            int objectStreamVersion = stream.readInt();
            logger.info(
                    LOGM_OBJECT_STREAM,
                    "SingleStreamIterator :: read OBJECT_STREAM_VERSION: {}",
                    () -> objectStreamVersion);
        } catch (IllegalArgumentException | IOException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "SingleStreamIterator :: got Exception when parse File {}",
                    file.getName(),
                    e);
            closeStream();
        }
    }

    /**
     * Initializes a SingleStreamIterator which consumes a stream which only contains SelfSerializable objects.
     * From this SingleStreamIterator we can get all SelfSerializable objects in the inputStream
     *
     * @param inputStream
     * 		a stream to be parsed
     */
    public SingleStreamIterator(InputStream inputStream) {
        stream = new SerializableDataInputStream(new BufferedInputStream(inputStream));
    }

    @Override
    public boolean hasNext() {
        try {
            if (streamClosed) {
                return false;
            }
            if (stream.available() == 0) {
                // close current stream
                closeStream();
                return false;
            }
            return true;
        } catch (IOException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new StreamParseErrorPayload("parseStream :: got IOException when readSerializable. "),
                    e);
            closeStream();
            return false;
        }
    }

    @Override
    public T next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        try {
            return stream.readSerializable();
        } catch (IOException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new StreamParseErrorPayload("parseStream :: got IOException when readSerializable. "),
                    e);
            closeStream();
            return null;
        }
    }

    /**
     * close current stream
     */
    public void closeStream() {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            logger.error(EXCEPTION.getMarker(), "SingleStreamIterator :: got IOException when closing stream. ", e);
        }
        streamClosed = true;
    }
}
