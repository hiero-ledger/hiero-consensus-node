// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

/**
 * Various reconnect modes for virtual map nodes.
 */
public final class VirtualMapReconnectMode {

    /**
     * "Push" reconnect mode, when teacher sends requests to learner, and learner responses if it has
     * the same virtual nodes
     */
    public static final String PUSH = "push";

    /**
     * "Pull / top to bottom" reconnect mode, when learner sends requests to teacher, rank by rank
     * starting from the root of the virtual tree, and teacher responses if it has the same virtual nodes
     */
    public static final String PULL_TOP_TO_BOTTOM = "pullTopToBottom";

    /**
     * "Pull / bottom to top" reconnect mode, when learner sends requests to teacher, starting from
     * leaf parent nodes, then leaves, and teacher responses if it has the same virtual nodes
     */
    public static final String PULL_TWO_PHASE_PESSIMISTIC = "pullTwoPhasePessimistic";

    /**
     * "Pull / parallel-synchronous" reconnect mode, when learner sends request to teacher, starting
     * from leaf parent nodes, then leaves. "Synchronous" means that learner doesn't send a request
     * for the next node, until a response about the last node is received from teacher. "Parallel"
     * indicates that internal nodes are processed in chunks, each chunk is sent in this sync mode,
     * but different chunks are processed independently in parallel
     */
    public static final String PULL_PARALLEL_SYNC = "pullParallelSync";

    private VirtualMapReconnectMode() {}
}
