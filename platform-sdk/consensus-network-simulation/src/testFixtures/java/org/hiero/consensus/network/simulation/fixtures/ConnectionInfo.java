// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation.fixtures;

import java.time.Duration;

/**
 * Holds configuration for a single connection between two nodes.
 *
 * @param latency the one-way propagation delay for this connection
 */
public record ConnectionInfo(Duration latency) {
    /** Zero-latency default connection used when no explicit configuration exists. */
    public static final ConnectionInfo DEFAULT = new ConnectionInfo(Duration.ZERO);
}
