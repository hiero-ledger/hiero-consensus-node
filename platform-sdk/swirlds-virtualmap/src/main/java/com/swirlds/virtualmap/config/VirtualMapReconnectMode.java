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

    private VirtualMapReconnectMode() {}
}
