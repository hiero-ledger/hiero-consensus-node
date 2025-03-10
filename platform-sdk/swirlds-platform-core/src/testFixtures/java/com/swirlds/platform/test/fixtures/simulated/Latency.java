// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.simulated;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * Latency for a single node in a hub-and-spoke model
 *
 * @param delay the delay of this node. The time for a message to reach a peer is the sum of this delay and the peer's
 *              delay
 */
public record Latency(@NonNull Duration delay) {

    /**
     * Returns {@code true} if this latency is equal to {@link Duration#ZERO};
     */
    public boolean isZero() {
        return delay.isZero();
    }
}
