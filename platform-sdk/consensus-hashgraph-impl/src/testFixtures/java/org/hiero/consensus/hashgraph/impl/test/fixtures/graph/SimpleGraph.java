// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.graph;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.hiero.base.crypto.Hash;

/**
 * A class that provides access to the events of a single graph.
 *
 * @param <T> the type of event provided
 */
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
    T event(int index);

    /**
     * Create a list of events from the provided indices.
     *
     * @param indices the indices of events to include in the list
     * @return the list of events
     */
    @NonNull
    List<T> events(@NonNull int... indices);

    /**
     * Get all events in a random order.
     *
     * @param random the instance of random to use for shuffling the events
     * @return the list of events
     */
    @NonNull
    List<T> shuffledEvents(@NonNull Random random);

    /**
     * Create a set of events from the provided indices.
     *
     * @param indices the indices of events to include in the set
     * @return the set of events
     */
    @NonNull
    Set<T> eventSet(@NonNull int... indices);

    /**
     * Get a set of event hashes from the provided indices.
     *
     * @param indices the indices of events to include in the set
     * @return the set of hashes
     */
    @NonNull
    Set<Hash> hashes(@NonNull int... indices);
}
