// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import java.time.Duration;
import org.hiero.consensus.gossip.impl.gossip.SyncException;

public class SyncTimeoutException extends SyncException {
    public SyncTimeoutException(final Duration syncTime, final Duration maxSyncTime) {
        super(String.format(
                "Maximum sync time exceeded! Max time: %d sec, time elapsed: %d sec",
                maxSyncTime.toSeconds(), syncTime.toSeconds()));
    }
}
