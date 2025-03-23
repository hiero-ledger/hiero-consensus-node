// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.node;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A cache for NodeId objects.
 *
 * It's useful to support code that uses long values to identify nodes (e.g. the Roster)
 * and needs to interact with code that uses NodeId objects for the same purpose
 * as it helps prevent creating many duplicate NodeId instances for the same long id value.
 */
final class NodeIdCache {
    private NodeIdCache() {}

    /** Minimum node id to cache. MUST BE non-negative. */
    private static final int MIN = 0;
    /** Maximum node id to cache. MUST BE non-negative, &gt;= MIN, and reasonably small. */
    private static final int MAX = 63;

    private static final NodeId[] CACHE = new NodeId[MAX - MIN + 1];

    static {
        for (int id = MIN; id <= MAX; id++) {
            CACHE[id - MIN] = new NodeId(id);
        }
    }

    /**
     * Fetch a NodeId value from the cache, or create a new NodeId object.
     * The caller MUST NOT mutate the returned object even though the NodeId class is technically mutable.
     * Note that whilst this class allows creation of NodeId with IDs beyond the boundary defined in this class,
     * it does not cache such NodeId instances as these are expected to be used for testing purposes only.
     *
     * @param id a node id value
     * @return a NodeId object
     */
    @NonNull
    static NodeId getOrCreate(final long id) {
        if (id >= MIN && id <= MAX) {
            return CACHE[(int) id - MIN];
        }
        return new NodeId(id);
    }
}
