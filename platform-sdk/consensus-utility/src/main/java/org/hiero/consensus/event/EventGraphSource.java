// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A source of events that form a graph.
 * Similar to {@link java.util.Iterator} but specialized for {@link PlatformEvent}.
 */
public interface EventGraphSource {

    /**
     * Returns the next event from this source.
     *
     * @return the next platform event
     * @throws java.util.NoSuchElementException if no more events are available
     */
    @NonNull
    PlatformEvent next();

    /**
     * Returns a list of the next {@code count} events from this source.
     *
     * @param count the number of events to return
     * @return a list of events
     */
    default List<PlatformEvent> nextEvents(final int count) {
        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!hasNext()) {
                throw new NoSuchElementException("Requested " + count + " events, but only " + i + " are available.");
            }
            events.add(next());
        }
        return events;
    }

    /**
     * Checks if there are more events available from this source.
     *
     * @return true if more events exist, false otherwise
     */
    boolean hasNext();

    /**
     * Performs the given action for each remaining event from this source.
     *
     * @param action the action to perform on each event
     */
    default void forEachRemaining(@NonNull final Consumer<PlatformEvent> action) {
        while (hasNext()) {
            action.accept(next());
        }
    }
}
