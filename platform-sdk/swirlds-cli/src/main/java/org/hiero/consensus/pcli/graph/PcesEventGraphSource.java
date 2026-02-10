// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import com.swirlds.common.context.PlatformContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.hiero.consensus.event.EventGraphSource;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.pces.impl.common.PcesFileReader;
import org.hiero.consensus.pces.impl.common.PcesFileTracker;
import org.hiero.consensus.pces.impl.common.PcesMultiFileIterator;

/**
 * An {@link EventGraphSource} that reads raw events from PCES files on disk in a streaming fashion.
 * Events are read one at a time as {@link #next()} is called, avoiding loading all events into memory.
 * Events are returned as-is without hashing or orphan buffer processing.
 * Consumers are responsible for hashing and handling orphan filtering if needed.
 */
public class PcesEventGraphSource implements EventGraphSource {

    private final PcesMultiFileIterator eventIterator;

    /**
     * Creates a source that reads raw events from PCES files at the given location.
     *
     * @param pcesLocation path to the directory containing PCES files
     * @param context      platform context for configuration
     */
    public PcesEventGraphSource(@NonNull final Path pcesLocation, @NonNull final PlatformContext context) {
        try {
            final PcesFileTracker pcesFileTracker = PcesFileReader.readFilesFromDisk(
                    context.getConfiguration(), context.getRecycleBin(), pcesLocation, 0, false);
            this.eventIterator = pcesFileTracker.getEventIterator(0, 0);
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
            return eventIterator.next();
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
}
