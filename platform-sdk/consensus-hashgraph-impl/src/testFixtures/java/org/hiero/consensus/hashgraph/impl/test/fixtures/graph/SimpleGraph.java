// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.graph;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import org.hiero.base.crypto.Hash;

public interface SimpleGraph<T> {
    /**
     * Get the list of all events in the graph.
     *
     * @return the list of events
     */
    @NonNull
    List<T> events();

    /**
     * Get a specific event by index.
     *
     * @param index the index of the event
     * @return the event
     */
    @NonNull
    T event(final int index);

    /**
     * Create a list of events from the provided indices.
     *
     * @param indices the indices of events to include in the list
     * @return the list of events
     */
    @NonNull
    List<T> events(@NonNull final int... indices);

    /**
     * Get all events in a random order.
     *
     * @return the list of events
     */
    @NonNull
    List<T> shuffledEvents();

    /**
     * Create a set of events from the provided indices.
     *
     * @param indices the indices of events to include in the set
     * @return the set of events
     */
    @NonNull
    Set<T> eventSet(@NonNull final int... indices);

    /**
     * Get a set of event hashes from the provided indices.
     *
     * @param indices the indices of events to include in the set
     * @return the set of hashes
     */
    @NonNull
    Set<Hash> hashes(@NonNull final int... indices);
}
