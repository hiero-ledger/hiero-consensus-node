// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** Used for searching for ancestors events in a hashgraph */
public class AncestorSearch {
    /** the iterator that returns ancestors */
    private final AncestorIterator iterator;

    /** Create a new ancestor search */
    public AncestorSearch() {
        this(new EventVisitedMark());
    }

    /**
     * Create a new ancestor search with the given mark
     * @param mark the instance to use to mark events as visited
     */
    public AncestorSearch(@NonNull final EventVisitedMark mark) {
        this.iterator = new AncestorIterator(mark);
    }

    /**
     * Gets an iterator over the set of ancestors of the given event (the root of the search) for which valid is true.
     * This will not include a valid ancestor that is only reachable through invalid ancestors.
     *
     * @param root the root event whose ancestors should be searched
     * @param valid do a depth-first search, but backtrack from any event e where valid(e)==false
     * @return an iterator over all valid ancestors of the root
     */
    public @NonNull AncestorIterator initializeSearch(
            @NonNull final EventImpl root, @NonNull final Predicate<EventImpl> valid) {
        iterator.initializeSearch(root, valid);
        return iterator;
    }

    /**
     * Finds events that are ancestors to all the supplied events. It will only traverse valid events,
     * where valid is defined by the predicate supplied. This method will also populate {@link
     * EventImpl#getRecTimes()} for the events returned.
     *
     * @param events the events whose ancestors we are searching for
     * @param valid checks if the event should be part of the search
     * @return a list of all common ancestors
     */
    public @NonNull List<EventImpl> commonAncestorsOf(
            @NonNull final List<EventImpl> events, @NonNull final Predicate<EventImpl> valid) {
        // each event visited by iterator from at least one of the provided events
        final ArrayList<EventImpl> visited = new ArrayList<>();
        // Do a non-recursive search of the hashgraph, without using the Java stack, and being
        // efficient when it's a DAG that isn't a tree.
        for (final EventImpl e : events) {
            final AncestorIterator validAncestors = initializeSearch(e, valid);
            while (validAncestors.hasNext()) {
                final EventImpl event = validAncestors.next();
                if (event.getRecTimes() == null) {
                    event.setRecTimes(new ArrayList<>());
                    visited.add(event);
                }
                event.getRecTimes().add(validAncestors.getTime());
            }
        }

        final ArrayList<EventImpl> commonAncestors = new ArrayList<>();
        for (final EventImpl e : visited) {
            if (e.getRecTimes().size() == events.size()) {
                commonAncestors.add(e);
                // sort the rec times so that they can be used to find the median when calculating
                // their consensus timestamps
                Collections.sort(e.getRecTimes());
            } else {
                // reclaim the memory for the list of received times
                e.setRecTimes(null);
            }
        }
        return commonAncestors;
    }
}
