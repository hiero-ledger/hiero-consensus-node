// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream.internal;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.stream.LinkedObjectStreamUtilities;
import com.swirlds.common.stream.StreamType;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.logging.legacy.payload.StreamParseErrorPayload;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.SelfSerializable;

/**
 * an Iterator from which we can get all SelfSerializable objects contained in given files;
 * the files are parsed in increasing order by file names;
 * the next file's startRunningHash is validated against the endRunningHash of previous file;
 * the first item is the startRunningHash in the first stream file,
 * then all stream objects contained in the directory,
 * the last item is the endRunningHash in the last stream file.
 * Instances of this class are not thread-safe.
 */
public class StreamFilesIterator<T extends SelfSerializable> implements Iterator<T> {
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(StreamFilesIterator.class);

    private static final Marker LOGM_OBJECT_STREAM_FILE = LogMarker.OBJECT_STREAM_FILE.getMarker();

    private static final Marker LOGM_EXCEPTION = LogMarker.EXCEPTION.getMarker();

    /**
     * an array of stream files to be parsed
     */
    private File[] files;
    /**
     * index of current file
     */
    private int idx;
    /**
     * startRunningHash in current file
     */
    private Hash startRunningHash;
    /**
     * endRunningHash in previous file
     */
    private Hash endRunningHash;
    /**
     * iterator of current stream file
     */
    private Iterator<T> currentFileIterator;
    /**
     * next object
     */
    private T next;
    /**
     * streamType of files to be parsed
     */
    private StreamType streamType;

    public StreamFilesIterator(final File[] files, final StreamType streamType) {
        this.streamType = streamType;
        this.files = Arrays.stream(files)
                .filter(streamType::isStreamFile)
                .sorted(Comparator.comparing(File::getName))
                .toArray(File[]::new);
        Arrays.sort(files, Comparator.comparing(File::getName));
        logger.info(
                LOGM_OBJECT_STREAM_FILE, "StreamFilesIterator : files to be parsed: {}", () -> Arrays.toString(files));
        idx = 0;
        endRunningHash = null;
        startRunningHash = null;
        currentFileIterator =
                idx < files.length ? LinkedObjectStreamUtilities.parseStreamFile(files[idx], streamType) : null;
    }

    @Override
    public boolean hasNext() {
        if (next != null) {
            return true;
        }
        return fetchNext();
    }

    @Override
    public T next() {
        T object = next;
        // set next to be null, so that
        next = null;
        // fetch the next object
        fetchNext();
        return object;
    }

    /**
     * try to read the next object
     *
     * @return whether the next object exits
     */
    private boolean fetchNext() {
        if (currentFileIterator == null || !currentFileIterator.hasNext() && idx == files.length - 1) {
            return false;
        }
        if (fetchNextInCurrentFileIterator()) {
            return true;
        } else {
            return readNextFile();
        }
    }

    /**
     * try to read the next object in current file Iterator
     *
     * @return whether the next object exits in current file Iterator
     */
    private boolean fetchNextInCurrentFileIterator() {
        if (!currentFileIterator.hasNext()) {
            return false;
        }
        T object = currentFileIterator.next();
        // whether current fileIterator contains next object
        boolean nextExists = true;
        if (object instanceof Hash) {
            if (startRunningHash == null) {
                // this is the startRunningHash of the first file, should output it
                next = object;
                // update startRunningHash
                startRunningHash = (Hash) object;
                logger.info(
                        LOGM_OBJECT_STREAM_FILE,
                        "StreamFilesIterator : first startRunningHash: {}",
                        () -> startRunningHash);
                return true;
            } else {
                // this is endRunningHash of current file
                // update endRunningHash
                endRunningHash = (Hash) object;
                logger.info(
                        LOGM_OBJECT_STREAM_FILE,
                        "StreamFilesIterator : update endRunningHash: {}",
                        () -> endRunningHash);
                if (idx == files.length - 1) {
                    // if current file is the last file, should output it
                    next = object;
                    logger.info(LOGM_OBJECT_STREAM_FILE, "StreamFilesIterator : last endRunningHash: {}", () -> object);
                } else {
                    nextExists = false;
                }
            }
        } else {
            next = object;
        }
        return nextExists;
    }

    /**
     * open the next file, read startRunningHash and validate it against endRunningHash;
     * if not match, log an error and return false;
     * else read and set next object
     *
     * @return
     */
    boolean readNextFile() {
        idx++;
        currentFileIterator = LinkedObjectStreamUtilities.parseStreamFile(files[idx], streamType);
        if (!currentFileIterator.hasNext()) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new StreamParseErrorPayload(String.format("Fail to parse %s", files[idx].getName())));
            return false;
        }
        T hash = currentFileIterator.next();
        if (!(hash instanceof Hash)) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new StreamParseErrorPayload(
                            String.format("The first item %s in %s is not Hash", hash, files[idx].getName())));
            return false;
        }
        // update startRunningHash
        startRunningHash = (Hash) hash;
        logger.info(
                LOGM_OBJECT_STREAM_FILE, "StreamFilesIterator : update startRunningHash: {}", () -> startRunningHash);
        // validate startRunningHash against endRunningHash of previous file
        if (!startRunningHash.equals(endRunningHash)) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new StreamParseErrorPayload(String.format(
                            "startRunningHash %s in %s doesn't match endRunningHash %s in %s",
                            startRunningHash, files[idx].getName(), endRunningHash, files[idx - 1].getName())));
            return false;
        } else if (!currentFileIterator.hasNext()) {
            // log error if the file is ended
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new StreamParseErrorPayload(
                            String.format("file %s only contains startRunningHash", files[idx].getName())));
            return false;
        } else {
            // output stream object
            next = currentFileIterator.next();
            return true;
        }
    }
}
