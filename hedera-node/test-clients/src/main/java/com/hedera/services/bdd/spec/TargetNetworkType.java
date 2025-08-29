// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec;

/**
 * Enumerates the different types of network that can be targeted by a test suite.
 */
public enum TargetNetworkType {
    /**
     * A network whose nodes are running in child subprocesses of the test process.
     */
    SUBPROCESS_NETWORK,
    /**
     * A long-lived remote network.
     */
    REMOTE_NETWORK,
    /**
     * An embedded "network" with a single Hedera instance whose workflows invoked directly, without gRPC.
     */
    EMBEDDED_NETWORK,
    /**
     * A network whose nodes are running in Docker containers.
     */
    OTTER_CONTAINER_NETWORK,
}
