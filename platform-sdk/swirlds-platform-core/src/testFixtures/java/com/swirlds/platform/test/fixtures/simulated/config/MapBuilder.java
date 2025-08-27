// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.simulated.config;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import org.hiero.consensus.model.node.NodeId;

/**
 * A convenience class for building maps with repeating elements, keyed by {@link NodeId}s with monotonically increasing
 * longs, starting at zero.
 */
public class MapBuilder<T> {
    private final Map<NodeId, T> map = new HashMap<>();
    private T lastElement = null;
    private Long lastIndex = 0L;

    private MapBuilder() {}

    /**
     * Creates a new builder of the specified type.
     *
     * @param type the class of the type of map to build
     * @param <T>  the type of the map to build
     * @return the new builder
     */
    public static <T> @NonNull MapBuilder<T> builder(@NonNull final Class<T> type) {
        return new MapBuilder<>();
    }

    /**
     * Sets the element to put in the map
     *
     * @param e the element
     * @return {@code this}
     */
    public @NonNull MapBuilder<T> useElement(@NonNull T e) {
        lastElement = e;
        return this;
    }

    /**
     * The number of times to put the element in the map. Elements are added with node ids with monotonically increasing
     * ids.
     *
     * @param num the number of times to repeat the element
     * @return {@code this}
     */
    public @NonNull MapBuilder<T> times(final int num) {
        for (int i = 0; i < num; i++) {
            map.put(NodeId.of(lastIndex++), lastElement);
        }
        return this;
    }

    /**
     * Builds the map. If the map is empty at the time of call, the last element set is added to the map once.
     *
     * @return the map
     */
    public @NonNull Map<NodeId, T> build() {
        if (map.isEmpty()) {
            map.put(NodeId.of(lastIndex++), lastElement);
        }
        return map;
    }
}
