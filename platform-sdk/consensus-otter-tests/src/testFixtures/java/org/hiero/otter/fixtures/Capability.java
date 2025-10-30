// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

/**
 * Capabilities that a test may require and is not supported by all environments.
 */
public enum Capability {

    /**
     * The test requires the ability to reconnect a node to the network.
     */
    RECONNECT,

    /**
     * The test requires the ability to build up back pressure in the wiring model.
     */
    BACK_PRESSURE,

    /** The test requires the ability for a single node to shut itself down by killing the JVM. */
    SINGLE_NODE_JVM_SHUTDOWN,

    /** The test requires access to a real network (not a simulated one). */
    USES_REAL_NETWORK,

    /** The test requires deterministic execution (e.g. no random delays). */
    DETERMINISTIC_EXECUTION;
}
