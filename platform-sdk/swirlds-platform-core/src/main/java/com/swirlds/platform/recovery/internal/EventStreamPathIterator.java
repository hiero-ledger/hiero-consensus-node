// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.internal;

import static com.swirlds.platform.recovery.internal.EventStreamLowerBound.UNBOUNDED;

import com.swirlds.common.io.IOIterator;
import com.swirlds.common.utility.BinarySearch;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.CesEvent;

/**
 * This iterator looks at a directory and iterates over the event stream files within it.
 */
public class EventStreamPathIterator implements Iterator<Path> {

    private final Iterator<Path> iterator;

    private static final String EVENT_FILE_EXTENSION = ".evts";

    public static final long FIRST_ROUND_AVAILABLE = 1;

    /**
     * Create an iterator that walks over event stream files within a directory.
     *
     * @param eventStreamDirectory
     * 		a directory containing event stream files
     * @param bound
     * 		return files guaranteed to contain all the events starting at the lower bound.
     * 		May return some files that contain data prior the lower bound. Will walk over
     * 		all event stream files if the lower bound is unbounded.
     * @throws IOException
     * 		if there is a problem reading files
     */
    public EventStreamPathIterator(@NonNull final Path eventStreamDirectory, @NonNull final EventStreamLowerBound bound)
            throws IOException {
        Objects.requireNonNull(eventStreamDirectory, "the event stream directory must not be null");
        Objects.requireNonNull(bound, "the lower bound must not be null");

        final List<Path> eventStreamFiles = new ArrayList<>();
        try (final Stream<Path> walk = Files.walk(eventStreamDirectory)) {
            walk.filter(EventStreamPathIterator::isFileAnEventStreamFile)
                    .sorted(EventStreamPathIterator::compareEventStreamPaths)
                    .forEachOrdered(eventStreamFiles::add);
        }

        if (eventStreamFiles.isEmpty()) {
            iterator = Collections.emptyIterator();
            return;
        }

        final CesEvent firstEvent = getFirstEventInEventStreamFile(eventStreamFiles.get(0));
        if (bound == UNBOUNDED || bound.compareTo(firstEvent) == 0) {
            // We are attempting to get events from the beginning of the event stream.
            iterator = eventStreamFiles.iterator();
        } else {
            final EventStreamLowerBound usedBound;
            if (bound instanceof final EventStreamRoundLowerBound roundBound) {
                // Since we are only checking the first event in each file, if the first event is of the desired round,
                // we can't be sure that there are not events of the same round at the end of the previous file.
                // Therefore, we need to start at the previous round to capture all events of the desired round.
                final long round = roundBound.getRound();
                final long usedRound = round == FIRST_ROUND_AVAILABLE ? FIRST_ROUND_AVAILABLE : round - 1;
                usedBound = new EventStreamRoundLowerBound(usedRound);
            } else {
                usedBound = bound;
            }

            // If the bound has a round, this binary search is guaranteed to return a file that contains events
            // from the round before where we want to start. As long as there are no gaps in the event stream files,
            // this guarantees that we will observe all the events from the target round.
            final int startingIndex =
                    (int) BinarySearch.throwingSearch(0, eventStreamFiles.size(), (final Long index) -> {
                        final Path eventStreamFile = eventStreamFiles.get(index.intValue());
                        return usedBound.compareTo(getFirstEventInEventStreamFile(eventStreamFile));
                    });

            iterator = eventStreamFiles
                    .subList(startingIndex, eventStreamFiles.size())
                    .iterator();
        }
    }

    /**
     * Compare two event stream files based on creation date.
     */
    private static int compareEventStreamPaths(final Path pathA, final Path pathB) {
        // A nice property of dates is that they are naturally alphabetized by timestamp
        return pathA.getFileName().compareTo(pathB.getFileName());
    }

    /**
     * Check if a file is an event stream file.
     */
    private static boolean isFileAnEventStreamFile(final Path path) {
        return path.toString().endsWith(EVENT_FILE_EXTENSION);
    }

    /**
     * Look inside an event stream file and return the first event
     *
     * @param path
     * 		a path to an event stream file
     * @return the DetailedConsensusEvent of the first event in the file
     */
    @NonNull
    private static CesEvent getFirstEventInEventStreamFile(@NonNull final Path path) throws IOException {
        try (final IOIterator<CesEvent> iterator = new EventStreamSingleFileIterator(path, true)) {
            if (!iterator.hasNext()) {
                throw new IllegalStateException("Event stream file contains no events");
            }
            return iterator.next();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        return iterator.hasNext();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Path next() {
        return iterator.next();
    }
}
