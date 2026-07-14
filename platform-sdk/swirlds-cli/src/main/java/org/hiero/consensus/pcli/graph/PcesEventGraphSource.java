// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.event.EventGraphSource;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.pces.impl.common.PcesFileReader;
import org.hiero.consensus.pces.impl.common.PcesFileTracker;
import org.hiero.consensus.pces.impl.common.PcesMultiFileIterator;

/**
 * An {@link EventGraphSource} that reads raw events from PCES files on disk in a streaming fashion. Events are read one
 * at a time as {@link #next()} is called, avoiding loading all events into memory. Events are returned as-is without
 * hashing or orphan buffer processing. Consumers are responsible for hashing and handling orphan filtering if needed.
 */
public class PcesEventGraphSource implements EventGraphSource {

    private final PcesFileTracker pcesFileTracker;
    private PcesMultiFileIterator eventIterator;
    private final boolean hashEvents;

    /**
     * Creates a source that reads raw events from PCES files at the given location.
     *
     * @param pcesLocation  path to the directory containing PCES files
     * @param configuration the platform configuration
     * @param recycleBin    the recycle bin for managing temporary files
     */
    public PcesEventGraphSource(
            @NonNull final Path pcesLocation,
            @NonNull final Configuration configuration,
            @NonNull final RecycleBin recycleBin) {
        this(pcesLocation, configuration, recycleBin, false, 0, 0);
    }

    /**
     * Creates a source that reads raw events from PCES files at the given location.
     *
     * @param pcesLocation  path to the directory containing PCES files
     * @param configuration the platform configuration
     * @param recycleBin    the recycle bin for managing temporary files
     */
    public PcesEventGraphSource(
            @NonNull final Path pcesLocation,
            @NonNull final Configuration configuration,
            @NonNull final RecycleBin recycleBin,
            final boolean hashEvents,
            final long startingRound,
            final long lowerBound) {
        try {
            this.pcesFileTracker =
                    PcesFileReader.readFilesFromDisk(configuration, recycleBin, pcesLocation, startingRound, false);
            this.eventIterator = pcesFileTracker.getEventIterator(lowerBound, startingRound);
            this.hashEvents = hashEvents;
        } catch (final IOException e) {
            throw new UncheckedIOException("Error initializing PCES file reader", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public PlatformEvent next() {
        try {
            final PlatformEvent event = eventIterator.next();
            if (hashEvents) {
                new PbjStreamHasher().hashEvent(event);
            }
            return event;
        } catch (final IOException e) {
            throw new UncheckedIOException("Error reading next event from PCES files", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        try {
            return eventIterator.hasNext();
        } catch (final IOException e) {
            throw new UncheckedIOException("Error checking for next event in PCES files", e);
        }
    }

    @Override
    public void reset() {
        this.eventIterator = pcesFileTracker.getEventIterator(0, 0);
    }
}
