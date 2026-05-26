// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation.fixtures;

import java.time.Duration;

public record ConnectionInfo(Duration latency) {
    public static final ConnectionInfo DEFAULT = new ConnectionInfo(Duration.ZERO);
}
