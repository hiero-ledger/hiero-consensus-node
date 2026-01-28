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
     * {@see PcesFile#getTimestamp()}
     */
    @NonNull
    Instant timestamp();

    /**
     * {@see PcesFile#getSequenceNumber()}
     */
    long sequenceNumber();

    /**
     * {@see PcesFile#getLowerBound()}
     */
    long lowerBound();

    /**
     * {@see PcesFile#getUpperBound()}
     */
    long upperBound();

    /**
     * {@see PcesFile#getOrigin()}
     */
    long origin();

    /**
     * {@see PcesFile#getPath()}
     */
    @NonNull
    Path path();

    /**
     * {@see PcesFile#getFileName()}
     */
    @NonNull
    String fileName();

    /**
     * {@see PcesFile#canContain(long)}
     */
    boolean canContain(long eventSequenceNumber);

    /**
     * {@see PcesFile#iterator(long)}
     */
    @NonNull
    IOIterator<PlatformEvent> iterator(long lowerBound) throws IOException;
}
