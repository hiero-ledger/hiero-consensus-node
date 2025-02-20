// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * An object that is able to read a swirlds log.
 */
public abstract class SwirldsLogReader<T> {

    /**
     * Contains a list of operations that are performed on each entry if a filter is passed.
     */
    private final List<FilterActionPair<T>> actions;

    /**
     * Create a new log reader.
     */
    public SwirldsLogReader() {
        this.actions = new LinkedList<>();
    }

    /**
     * Extract the next entry from the log source (e.g. a log file).
     */
    protected abstract T readNextEntry() throws IOException;

    /**
     * Collect log entries that match a filter. The list returned by this method will be updated with matching log
     * entries as the log is read.
     *
     * A maximum of 10,000 entries will be collected to prevent memory exhaustion.
     *
     * @param filter
     * 		a filter that is applied to each log entry. If the predicate returns true then the entry is allowed
     * 		to pass, otherwise it is rejected. If null then all entries are considered to pass the filter.
     * @return a list that is updated as the log is read
     */
    public List<T> collect(final Predicate<T> filter) {
        return collect(filter, 10_000);
    }

    /**
     * Collect log entries that match a filter. The list returned by this method will be updated with matching log
     * entries as the log is read.
     *
     * @param filter
     * 		a filter that is applied to each log entry. If the predicate returns true then the entry is allowed
     * 		to pass, otherwise it is rejected. If null then all entries are considered to pass the filter.
     * @param limit
     * 		the maximum number of entries to collect
     * @return a list that is updated as the log is read
     */
    public List<T> collect(final Predicate<T> filter, int limit) {
        final List<T> matches = new LinkedList<>();
        addAction(filter, (T entry) -> {
            if (matches.size() < limit) {
                matches.add(entry);
            }
        });
        return matches;
    }

    /**
     * Count the number of entries that match a filter.
     *
     * @param filter
     * 		a filter that is applied to each log entry. If the predicate returns true then the entry is allowed
     * 		to pass, otherwise it is rejected.
     * @return a integer that holds the count of matched entries.
     */
    public AtomicInteger count(final Predicate<T> filter) {
        // Note: we are not using atomic because of thread safety. Rather, we just need an object that represents
        // an integer so that we can pass it as a reference.
        final AtomicInteger count = new AtomicInteger(0);
        addAction(filter, (T entry) -> count.getAndIncrement());
        return count;
    }

    /**
     * Run a function on each log entry that passes a given filter.
     *
     * @param filter
     * 		the filter used to select log entries. If the predicate returns true then the entry is allowed
     * 		to pass, otherwise it is rejected. If null then all entries are considered to pass the filter.
     * @param action
     * 		a function that is called on each matching entry
     */
    public void addAction(final Predicate<T> filter, final Consumer<T> action) {
        actions.add(new FilterActionPair<T>(filter, action));
    }

    /**
     * Get the next entry in the log.
     *
     * @return the next entry or null if the end of the log is reached
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public T nextEntry() throws IOException {
        return nextEntry(null);
    }

    /**
     * Get the next entry that passes a particular filter.
     *
     * @param filter
     * 		the filter to use to select the next entry to return. If the predicate returns true then the entry is
     * 		allowed to pass, otherwise it is rejected. If null then allow all entries to pass.
     * @return the next entry that passes or null if the end of the log is reached
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public T nextEntry(final Predicate<T> filter) throws IOException {
        while (true) {
            T entry = readNextEntry();
            if (entry == null) {
                // No more entries
                return null;
            }

            for (FilterActionPair<T> action : actions) {
                action.handle(entry);
            }

            if (filter == null || filter.test(entry)) {
                // Entry matches filter, return it
                return entry;
            }
        }
    }

    /**
     * Read all entries from the log, adding them to the appropriate lists (as specified by
     * {@link SwirldsLogReader#collect(Predicate)} and calling the appropriate callbacks (as specified by
     * {@link SwirldsLogReader#addAction(Predicate, Consumer)}.
     *
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public void readFully() throws IOException {
        T entry;
        do {
            entry = nextEntry();
        } while (entry != null);
    }
}
