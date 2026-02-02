// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A source of events to be processed by an {@link EventGraphPipeline}.
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
