// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.event.preconsensus.PcesFileManager.NO_LOWER_BOUND;

import com.swirlds.common.utility.RandomAccessDeque;
import com.swirlds.common.utility.UnmodifiableIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Tracks preconsensus event files currently on disk.
 */
public class PcesFileTracker {
    private static final Logger logger = LogManager.getLogger(PcesFileTracker.class);

    /**
     * The initial size of the ring buffer used to track event files.
     */
    private static final int INITIAL_RING_BUFFER_SIZE = 1024;

    /**
     * Tracks all files currently on disk.
     */
    private final RandomAccessDeque<PcesFile> files = new RandomAccessDeque<>(INITIAL_RING_BUFFER_SIZE);

    /**
     * Constructor.
     */
    public PcesFileTracker() {}

    /**
     * Get the first file in the file list.
     *
     * @return the first file in the file list
     */
    public PcesFile getFirstFile() {
        return files.getFirst();
    }

    /**
     * Get the last file in the file list.
     *
     * @return the last file in the file list
     */
    public PcesFile getLastFile() {
        return files.getLast();
    }

    /**
     * Remove the first file in the file list.
     *
     * @return the file that was removed
     */
    public PcesFile removeFirstFile() {
        return files.removeFirst();
    }

    /**
     * Remove the last file in the file list.
     *
     * @return the file that was removed
     */
    public PcesFile removeLastFile() {
        return files.removeLast();
    }

    /**
     * Get the number of files currently being tracked.
     *
     * @return the number of files currently being tracked
     */
    public int getFileCount() {
        return files.size();
    }

    /**
     * Get the number of bytes in all files currently being tracked.
     *
     * @return the number of bytes in all files currently being tracked
     * @throws IOException if there is an error reading the files
     */
    public long getTotalFileByteCount() throws IOException {
        long totalFileByteCount = 0;

        // Measure the size of each file.
        for (final PcesFile file : files) {
            totalFileByteCount += Files.size(file.getPath());
        }

        return totalFileByteCount;
    }

    /**
     * Add a new files to the end of the file list.
     *
     * @param file the file to be added
     */
    public void addFile(@NonNull final PcesFile file) {
        Objects.requireNonNull(file);
        files.addLast(file);
    }

    /**
     * Get the file at a specified index.
     *
     * @param index the index of the file to get
     * @return the file at the specified index
     */
    @NonNull
    public PcesFile getFile(final int index) {
        return files.get(index);
    }

    /**
     * Set the file at a specified index.
     *
     * @param index the index of the file to set
     * @param file  the file to set
     */
    public void setFile(final int index, @NonNull final PcesFile file) {
        Objects.requireNonNull(file);
        files.set(index, file);
    }

    /**
     * Get an iterator that walks over all events starting with a specified lower bound.
     * <p>
     * Note: this method only works at system startup time, using this iterator after startup has undefined behavior. A
     * future task will be to enable event iteration after startup.
     *
     * @param lowerBound    the desired lower bound, iterator is guaranteed to return all available events with an
     *                      ancient indicator (the birth round of events)
     *                      greater or equal to this value. No events with a smaller ancient identifier will be
     *                      returned. A value of {@link PcesFileManager#NO_LOWER_BOUND} will cause the returned iterator
     *                      to walk over all available events.
     * @param startingRound the round to start iterating from
     * @return an iterator that walks over events
     */
    @NonNull
    public PcesMultiFileIterator getEventIterator(final long lowerBound, final long startingRound) {
        return new PcesMultiFileIterator(lowerBound, getFileIterator(lowerBound, startingRound));
    }

    /**
     * Get an iterator that walks over all event files currently being tracked, in order.
     * <p>
     * Note: this method only works at system startup time, using this iterator after startup has undefined behavior. A
     * future task will be to enable event iteration after startup.
     *
     * @param lowerBound  the desired lower bound, iterator is guaranteed to walk over all files that may contain events
     *                    with an ancient indicator (i.e. birth round greater or equal to this value. A value of
     *                    {@link PcesFileManager#NO_LOWER_BOUND} will cause the returned iterator to walk over all
     *                    available event files.
     * @param originRound the origin round to start iterating from. The origin of a PCES segment is used to
     *                    differentiate segments of PCES files separated by discontinuities.
     * @return an unmodifiable iterator that walks over event files in order
     */
    @NonNull
    public Iterator<PcesFile> getFileIterator(final long lowerBound, final long originRound) {
        final int firstFileIndex = getFirstRelevantFileIndex(originRound);

        // Edge case: we want all events regardless of lower bound
        if (lowerBound == NO_LOWER_BOUND) {
            return new UnmodifiableIterator<>(files.iterator(firstFileIndex));
        }

        // Edge case: there are no files
        if (files.size() == 0) {
            logger.warn(STARTUP.getMarker(), "No preconsensus event files available");
            return Collections.emptyIterator();
        }

        // Edge case: our first file comes after the requested lower bound
        if (files.get(firstFileIndex).getLowerBound() >= lowerBound) {
            // Unless we observe at least one file with a lower bound less than the requested minimum,
            // then we can't know for certain that we have all data for the requested lower bound.
            logger.warn(
                    STARTUP.getMarker(),
                    "The preconsensus event stream has insufficient data to guarantee that all events with the "
                            + "requested lower bound of {} are present, the first file has a lower bound of {}",
                    lowerBound,
                    files.get(firstFileIndex).getLowerBound());

            return new UnmodifiableIterator<>(files.iterator(firstFileIndex));
        }

        // Edge case: all of our data comes before the requested lower bound
        if (files.getLast().getUpperBound() < lowerBound) {
            logger.warn(
                    STARTUP.getMarker(),
                    "The preconsensus event stream has insufficient data to guarantee that "
                            + "all events with the requested lower bound of {} are present, "
                            + "the last file has a lower bound of {}",
                    lowerBound,
                    files.getLast().getUpperBound());
            return Collections.emptyIterator();
        }

        // Standard case: we need to stream data starting from a file somewhere in the middle of stream
        final int fileCount = files.size();
        for (int index = firstFileIndex; index < fileCount; index++) {
            final PcesFile file = files.get(index);
            if (file.getUpperBound() >= lowerBound) {
                // We have found the first file that may contain events at the requested lower bound.
                return new UnmodifiableIterator<>(files.iterator(index));
            }
        }

        // It should not be possible to reach this point.
        throw new IllegalStateException("Failed to find a file that may contain events at the requested lower bound");
    }

    /**
     * Get an iterator that walks over all event files currently being tracked, in order. No filtering is applied.
     *
     * @return an unmodifiable iterator that walks over event files in order
     */
    public Iterator<PcesFile> getFileIterator() {
        return new UnmodifiableIterator<>(files.iterator());
    }

    /**
     * Get the index of the first file to consider given a certain starting round. This will be the file with the
     * largest origin that does not exceed the starting round.
     * <p>
     * If no file is compatible with the starting round, return -1. If there are no compatible files, that means there
     * are either no files, or all files have an origin that exceeds the starting round.
     *
     * @param originRound the origin round to start streaming from
     * @return the index of the first file to consider for a given starting round
     */
    public int getFirstRelevantFileIndex(final long originRound) {
        // When streaming from the preconsensus event stream, we need to start at
        // the file with the largest origin that does not exceed the starting round.

        int candidateIndex = -1;
        long candidateOrigin = -1;

        for (int index = 0; index < files.size(); index++) {
            final long fileOrigin = files.get(index).getOrigin();

            if (fileOrigin > originRound) {
                // Once we find the first file with an origin that exceeds the starting round, we can stop searching.
                // File origins only increase, so we know that all files after this one will also exceed the round.
                return candidateIndex;
            } else if (fileOrigin > candidateOrigin) {
                // We've discovered a higher legal origin. Keep track of the first file with this origin.
                candidateIndex = index;
                candidateOrigin = fileOrigin;
            }
        }

        // If all files have a legal origin, return the index of the first file with the highest index.
        return candidateIndex;
    }
}
