// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.pces.impl.common.PcesFile;

/**
 * A ReadablePcesFile provides read-only access to the metadata and events stored in a PCES file.
 */
public interface ReadablePcesFile {

    /**
     * {@see PcesFile#EVENT_FILE_EXTENSION}
     */
    String EVENT_FILE_EXTENSION = PcesFile.EVENT_FILE_EXTENSION;

    /**
     * {@see PcesFile#EVENT_FILE_SEPARATOR}
     */
    String EVENT_FILE_SEPARATOR = PcesFile.EVENT_FILE_SEPARATOR;

    /**
     * {@see PcesFile#SEQUENCE_NUMBER_PREFIX}
     */
    String SEQUENCE_NUMBER_PREFIX = PcesFile.SEQUENCE_NUMBER_PREFIX;

    /**
     * {@see PcesFile#MINIMUM_BIRTH_ROUND_PREFIX}
     */
    String MINIMUM_BIRTH_ROUND_PREFIX = PcesFile.MINIMUM_BIRTH_ROUND_PREFIX;

    /**
     * {@see PcesFile#MAXIMUM_BIRTH_ROUND_PREFIX}
     */
    String MAXIMUM_BIRTH_ROUND_PREFIX = PcesFile.MAXIMUM_BIRTH_ROUND_PREFIX;

    /**
     * {@see PcesFile#ORIGIN_PREFIX}
     */
    String ORIGIN_PREFIX = PcesFile.ORIGIN_PREFIX;

    /**
     * @return the timestamp when this file was created (wall clock time)
     */
    @NonNull
    Instant timestamp();

    /**
     * @return the sequence number of the file. All file sequence numbers are unique. Sequence numbers are allocated in
     * monotonically increasing order.
     */
    long sequenceNumber();

    /**
     * @return the minimum event bound permitted to be in this file (inclusive), based on the birth round of events.
     */
    long lowerBound();

    /**
     * @return the maximum event generation permitted to be in this file (inclusive), based on the birth round of events.
     */
    long upperBound();

    /**
     * Get the origin of the stream containing this file. A stream's origin is defined as the round number after which
     * the stream is unbroken. When the origin of two sequential files is different, this signals a discontinuity in the
     * stream (i.e. the end of one stream and the beginning of another). When replaying events, it is never ok to stream
     * events from files with different origins.
     *
     * @return the origin round number
     */
    long origin();

    /**
     * @return the path to this file
     */
    @NonNull
    Path path();

    /**
     * Get the file name of this file.
     *
     * @return this file's name
     */
    @NonNull
    String fileName();

    /**
     * Check if it is legal for the file described by this object to contain a particular event.
     *
     * @param eventSequenceNumber a sequence number that describes which file an event should be in, based on the birth round of events.
     * @return true if it is legal for this event to be in the file described by this object
     */
    boolean canContain(long eventSequenceNumber);

    /**
     * Get an iterator that walks over the events in this file. The iterator will only return events that have an
     * ancient indicator that is greater than or equal to the lower bound.
     *
     * @param lowerBound lower bound of the events to return, based on the birth round of events.
     * @return an iterator over the events in this file
     * @throws IOException if an I/O error occurs
     */
    @NonNull
    IOIterator<PlatformEvent> iterator(long lowerBound) throws IOException;
}
