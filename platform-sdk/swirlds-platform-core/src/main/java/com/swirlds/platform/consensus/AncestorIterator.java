// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

/**
 * This is an iterator over all valid ancestors of a given root event, that are reachable through
 * valid ancestors. The "valid" ancestors are defined by a lambda predicate passed in to the
 * {@link #initializeSearch(EventImpl, Predicate)} method.
 *
 * <p>It can be used to read or modify the events. This does not implement Collection, because its
 * only purpose to allow iteration over those events. The iteration is depth first, and backtracks
 * each time it reaches an invalid event (one for which the predicate returns false).
 *
 * <p>It is not threadsafe, and will silently fail without throwing any exceptions if you attempt to
 * create and use two at the same time, even if they are in the same thread, and even if they are
 * only used to read and not write. So always create only one at a time, and ensure that you are
 * done with it before creating another one.
 *
 * <p>This returns all ancestors of the root event that are valid. It iterates in an order that
 * always returns a parent before its child. The root itself is considered to be one of the
 * ancestors, and is last in the iteration.
 *
 * <p>Recursion happens on self parents before other parents. So if there are multiple paths from
 * the root to an event, it will use the path that stays on a line of self parents for as far down
 * as possible before leaving that line.
 */
public class AncestorIterator implements Iterator<EventImpl> {
    private static final int INITIAL_STACK_SIZE = 300;
    private final EventVisitedMark mark;
    /** stack of EventImpl on the path to curr */
    private final Deque<EventImpl> stackRef = new ArrayDeque<>(INITIAL_STACK_SIZE);
    /** stack of state */
    private final Deque<IteratorState> stackState = new ArrayDeque<>(INITIAL_STACK_SIZE);
    /** stack of selfAncestor */
    private final Deque<Boolean> stackSelfAncestor = new ArrayDeque<>(INITIAL_STACK_SIZE);
    /** stack of timeReachedRoot */
    private final Deque<Instant> stackTime = new ArrayDeque<>(INITIAL_STACK_SIZE);
    /** the current event reached in the search */
    private EventImpl curr;
    /**
     * a lambda which filters which ancestors are of interest: only each event e for which
     * valid(e)==true
     */
    private Predicate<EventImpl> valid;
    /**
     * the time when the event last returned by the iterator's next() first reached the creator of
     * root
     */
    private Instant timeReachedRoot;
    /** becomes false when done and hasNext should return false */
    private boolean hasNext = false;
    /** the state of the state machine searching from curr */
    private IteratorState state;
    /** is curr a self ancestor of the judge? */
    private boolean selfAncestor;

    private enum IteratorState {
        TRAVERSING_SELF_PARENT,
        TRAVERSING_OTHER_PARENT,
        BOTTOM
    }

    /**
     * Create an iterator that will iterate over all ancestors of a given event
     * @param mark the instance to use to mark events as visited
     */
    public AncestorIterator(@NonNull final EventVisitedMark mark) {
        this.mark = mark;
    }

    /**
     * The set of ancestors of the given event (the root of the search) for which valid is true.
     * This will not include a valid ancestor that is only reachable through invalid ancestors.
     *
     * @param root the root event whose ancestors should be searched
     * @param predicate do a depth-first search, but backtrack from any event e where
     *     valid(e)==false
     */
    public void initializeSearch(@NonNull final EventImpl root, @NonNull final Predicate<EventImpl> predicate) {
        clear();

        // use the next mark, so that currently marked events will not be found until they are
        // marked again
        mark.nextMark();
        curr = root;
        valid = predicate;
        timeReachedRoot = root.getTimeCreated(); // ancestors of curr reached creator then
        hasNext = true;
        state = IteratorState.TRAVERSING_SELF_PARENT;
        selfAncestor = true;
    }

    private void clear() {
        stackRef.clear();
        stackState.clear();
        stackSelfAncestor.clear();
        stackTime.clear();
        curr = null;
        valid = null;
        timeReachedRoot = null;
        hasNext = false;
        state = null;
        selfAncestor = false;
    }

    /**
     * @return the time when the event last returned by the iterator first reached a self-ancestor
     *     of the root. To phrase it differently, it is the timestamp of the oldest self-ancestor of
     *     root that is also an ancestor of the last event returned.
     */
    public @NonNull Instant getTime() {
        return timeReachedRoot;
    }

    /**
     * Returns {@code true} if the iteration has more elements. (In other words, returns {@code
     * true} if {@link #hasNext} would return an element rather than throwing an exception.)
     *
     * @return {@code true} if the iteration has more elements
     */
    @Override
    public boolean hasNext() {
        return hasNext;
    }

    /**
     * Returns the next element in the iteration.
     *
     * @return the next element in the iteration
     * @throws NoSuchElementException if the iteration has no more elements
     */
    @Override
    public @NonNull EventImpl next() {
        if (!hasNext) {
            throw new NoSuchElementException("no more events left to iterate over");
        }
        // keep looking until we reach the return statement in the case state == BOTTOM
        while (true) {
            mark.markVisited(curr); // mark this event, so we don't explore it again later
            switch (state) {
                case TRAVERSING_SELF_PARENT -> { // try to traverse into selfParent
                    final EventImpl parent = curr.getSelfParent();
                    state = IteratorState.TRAVERSING_OTHER_PARENT;
                    if (mark.isNotVisited(parent) && valid.test(parent)) {
                        stackRef.push(curr);
                        stackState.push(state);
                        stackSelfAncestor.push(selfAncestor);
                        stackTime.push(timeReachedRoot);
                        curr = parent;
                        state = IteratorState.TRAVERSING_SELF_PARENT;
                        if (selfAncestor) {
                            timeReachedRoot = curr.getTimeCreated(); // ancestors of curr reached creator then
                        }
                    } // there is no selfParent, or it was already visited, or it was not valid
                }
                case TRAVERSING_OTHER_PARENT -> { // try to traverse into otherParent
                    final EventImpl parent = curr.getOtherParent();
                    state = IteratorState.BOTTOM;
                    if (mark.isNotVisited(parent) && valid.test(parent)) {
                        stackRef.push(curr);
                        stackState.push(state);
                        stackSelfAncestor.push(selfAncestor);
                        stackTime.push(timeReachedRoot);
                        curr = parent;
                        state = IteratorState.TRAVERSING_SELF_PARENT;
                        // first step off the selfAncestor path makes all the events below false
                        selfAncestor = false;
                    }
                    // else there is no otherParent, or it was already visited, or it was not valid
                }
                case BOTTOM -> { // done with ancestors of curr, so return curr then backtrack
                    if (stackRef.isEmpty()) { // if we're back to the root
                        hasNext = false; // then there are no more
                        return curr; // return this root
                    }
                    final EventImpl toReturn = curr; // else we are done with all the descendents, so backtrack
                    curr = stackRef.pop();
                    state = stackState.pop();
                    selfAncestor = stackSelfAncestor.pop();
                    timeReachedRoot = stackTime.pop();
                    return toReturn; // return the child of the vertex we just backtracked to
                }
                default -> throw new IllegalStateException("Unknown state: " + state);
            }
        }
    }
}
